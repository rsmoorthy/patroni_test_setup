import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Created by Rahul Kumar on 6/16/20
 */
public class DBCLusterTestWithConnectionPool {

  public static void main(String[] args) {
    System.out.println("Started " + new Date());
    if (args.length < 2) {
      System.out.println("ERROR: Expected arguments\n1: noOfThreads\n2: loopCount");
      return;
    }
    int noOfThreads = Integer.parseInt(args[0]);
    int loopCount = Integer.parseInt(args[1]);
    DBTest dbTest = new DBTest(noOfThreads, loopCount);
    dbTest.performTask();
    System.out.println("Completed " + new Date());
  }
}

class DBTest {

  private final int noOfThreads;
  private final int loopCount;
  private final Map<Integer, List<Connection>> connectionPool = new HashMap<>(3);
  private final Map<Integer, List<Connection>> usedConnectionPool = new HashMap<>(3);

  public DBTest(int noOfThreads, int loopCount) {
    this.noOfThreads = noOfThreads;
    this.loopCount = loopCount;
    try (FileReader reader = new FileReader("dbcluster.properties")) {
      Properties properties = new Properties();
      properties.load(reader);
      String dbUsername = properties.getProperty("db.username");
      String dbPassword = properties.getProperty("db.password");
      String[] dbUrls = new String[3];
      dbUrls[0] = properties.getProperty("leader.db.url");
      dbUrls[1] = properties.getProperty("replica1.db.url");
      dbUrls[2] = properties.getProperty("replica2.db.url");
      long start = System.currentTimeMillis();
      System.out.println("Creating Connections");
      for (int i = 0; i < 3; i++) {
        List<Connection> list = connectionPool.getOrDefault(i, new ArrayList<>(noOfThreads));
        for (int j = 0; j < noOfThreads; j++) {
          list.add(DriverManager.getConnection(dbUrls[i], dbUsername, dbPassword));
        }
        connectionPool.put(i, list);
        usedConnectionPool.put(i, new ArrayList<>());
      }
      System.out.println("Connections created : " + noOfThreads
          + " : Time :" + (System.currentTimeMillis() - start));
      cleanup();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void cleanup() throws SQLException {
    Connection conn = getConnection(0);
    String dropTable = "DROP TABLE IF EXISTS db_cluster_test";
    try (PreparedStatement ps = conn.prepareStatement(dropTable)) {
      ps.executeUpdate();
    }
    String createTable = "CREATE TABLE db_cluster_test (id_test varchar(20) primary key)";
    try (PreparedStatement ps = conn.prepareStatement(createTable)) {
      ps.executeUpdate();
    }
    releaseConnection(conn, 0);
  }

  @SuppressWarnings("BusyWait")
  public void performTask() {
    ExecutorService executorService = Executors.newFixedThreadPool(noOfThreads);
    ThreadPoolExecutor executor = (ThreadPoolExecutor) executorService;
    final int[] replica1Status = new int[2];
    final int[] replica2Status = new int[2];
    try {
      System.out.println("Running Suite");
      for (int i = 1; i <= loopCount; i++) {
        for (int j = 0; j < noOfThreads; j++) {
          final String id = "request" + i + "_" + j;
          executor.execute(() -> {
            try {
              insert(id, 0); //  type = 0 ===> LEADER
            } catch (SQLException e) {
              System.out.println(new Date() + " : Exception :: INSERT : " + e);
            }
            try {
              boolean[] ret = select(id);
              if (ret[0]) {
                replica1Status[0]++;
              } else {
                replica1Status[1]++;
              }
              if (ret[1]) {
                replica2Status[0]++;
              } else {
                replica2Status[1]++;
              }
            } catch (SQLException | InterruptedException e) {
              System.out.println(new Date() + " : Exception :: SELECT : " + e);
            }
            try {
              delete(id, 0); //  type = 0 ===> LEADER
            } catch (SQLException e) {
              System.out.println(new Date() + " : Exception :: DELETE : " + e);
            }
          });
        }
      }
      System.out.println(executor);
      while (executor.getActiveCount() != 0) {
        Thread.sleep(10000);
        System.out.println(executor);
      }
      executor.shutdown();

      System.out.println(
          new Date() + " :: REPLICA 1 :: Success: " + replica1Status[0] + " Failed: "
              + replica1Status[1] + " NoOfThreads: "
              + noOfThreads + " LoopCount: " + loopCount);
      System.out.println(
          new Date() + " :: REPLICA 2 :: Success: " + replica2Status[0] + " Failed: "
              + replica2Status[1] + " NoOfThreads: "
              + noOfThreads + " LoopCount: " + loopCount);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      executor.shutdownNow();
    }
  }

  private void delete(String id, int type) throws SQLException {
    Connection conn = getConnection(type);
    try {
      String query = "DELETE FROM db_cluster_test WHERE id_test = '" + id + "'";
      try (PreparedStatement ps = conn.prepareStatement(query)) {
        ps.execute();
      }
    } finally {
      releaseConnection(conn, type);
    }
  }

  private boolean[] select(String id) throws SQLException, InterruptedException {
    int type1 = 1;   //  REPLICA 1
    int type2 = 2;   //  REPLICA 2
    long start = System.currentTimeMillis();
    boolean[] flags = new boolean[2];
    Connection conn1 = getConnection(type1);
    Connection conn2 = getConnection(type2);
    try {
      Thread t1 = new Thread() {
        @Override
        public void run() {
          try {
            flags[0] = selectQuery(id, start, conn1, type1);
          } catch (SQLException e) {
            System.out.println("EXCEPTION : SELECT : Thread : t1 : " + this + " : " + e);
          }
        }
      };
      Thread t2 = new Thread() {
        @Override
        public void run() {
          try {
            flags[1] = selectQuery(id, start, conn2, type2);
          } catch (SQLException e) {
            System.out.println("EXCEPTION : SELECT : Thread : t1 : " + this + " : " + e);
          }
        }
      };
      t1.start();
      t2.start();
      t1.join();
      t2.join();
      return flags;
    } finally {
      releaseConnection(conn1, type1);
      releaseConnection(conn2, type2);
    }
  }

  private boolean selectQuery(String id, long start, Connection conn, int type)
      throws SQLException {
    boolean flag;
    String query = "SELECT * FROM db_cluster_test WHERE id_test = '" + id + "'";
    try (PreparedStatement ps = conn.prepareStatement(query)) {
      try (ResultSet rs = ps.executeQuery()) {
        flag = rs.next();
      }
    }
    if (!flag) {
      System.out.println(
          new Date() + " : " + conn + " : REPLICA " + type + " : Record doesn't exists\t:\t"
              + id + "\tTime : " + (System.currentTimeMillis() - start));
    }
    return flag;
  }

  private void insert(String id, int type) throws SQLException {
    Connection conn = getConnection(type);
    try {
      String query = "INSERT INTO db_cluster_test(id_test) VALUES ('" + id + "')";
      try (PreparedStatement ps = conn.prepareStatement(query)) {
        ps.execute();
      }
    } finally {
      releaseConnection(conn, type);
    }
  }

  private synchronized Connection getConnection(int type) {
    Connection connection = connectionPool.get(type).remove(connectionPool.get(type).size() - 1);
    usedConnectionPool.get(type).add(connection);
    return connection;
  }

  private synchronized boolean releaseConnection(Connection connection, int type) {
    connectionPool.get(type).add(connection);
    return usedConnectionPool.get(type).remove(connection);
  }
}