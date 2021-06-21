const { Client, Pool } = require("pg")
const MAX_CONNECTIONS = process.argv[3] ? process.argv[3] : process.env.MAXCONNS ? parseInt(process.env.MAXCONNS) : 30
const MAX_LOOPS = process.argv[4] ? process.argv[4] : process.env.MAXLOOPS ? parseInt(process.env.MAXLOOPS) : 5
const SYNCOMMIT = process.env.SYNCOMMIT || null
const USE_RLB = process.env.USE_RLB ? process.env.USE_RLB : false

var connections = {
  primary: {},
  secondary1: {},
  secondary2: {}
}

var updated = 0
var failures = [0, 0, 0]
var success = 0

const sleep = duration => {
  return new Promise((resolve, reject) => {
    setTimeout(() => resolve(), duration)
  })
}

const defaultConnect = {
  user: "postgres",
  host: process.env.PGHOST || "patroni1",
  database: "postgres",
  password: "postgres",
  port: 5432
}

const connectDetails = {
  primary: {
    ...defaultConnect,
    host: process.env.PGHOST || "patroni1"
  },
  secondary1: {
    ...defaultConnect,
    host: process.env.PGSEC2 || "patroni2"
  },
  secondary2: {
    ...defaultConnect,
    host: process.env.PGSEC3 || "patroni3",
    port: 5432
  }
}

const closeEvent = (type, idx, err) => {
  // console.log("connection closed", type, idx, err)
}

const connect = async (idx, server = "primary") => {
  if (connections[server][idx]) return connections[server][idx]

  let client = new Client(connectDetails[server])
  client.on("error", err => closeEvent("error", idx, err))
  client.on("end", err => closeEvent("end", idx, err))
  let ret = await client.connect()
  if (SYNCOMMIT) {
    ret = await client.query(`set session synchronous_commit to ${SYNCOMMIT}`)
  }
  connections[server][idx] = client
  return connections[server][idx]
}

const doAllConnect = async () => {
  for (let i = 1; i <= MAX_CONNECTIONS; i++) {
    await connect(i, "primary")
    if (!USE_RLB) {
      await connect(i, "secondary1")
      await connect(i, "secondary2")
    }
  }
}

const doAllClose = async server => {
  for (let i = 1; i <= MAX_CONNECTIONS; i++) {
    if (!connections[server][i]) continue
    connections[server][i].end()
    connections[server][i] = null
  }
}

const run = async idx => {
  let primary = connections["primary"][idx]
  try {
    let tm = parseInt(new Date().getTime() / 1000)

    // Do the insert in a single transaction
    let ret = await primary.query("begin")
    if (SYNCOMMIT) {
      ret = await primary.query(`set local synchronous_commit to ${SYNCOMMIT}`)
    }
    ret = await primary.query(`insert into synchtest (id, value) values (${idx}, ${tm})`)
    ret = await primary.query("commit") // close the single transaction

    // Now let us query the database (both secondaries and also the primary for the just inserted value)
    let val = []
    if (USE_RLB) {
      // RLB is a software that is a replacement for pgpool, that routes and load balances queries
      // For this synchtest, we can ignore this loop and not use RLB
      ret = await primary.query(`select value from synchtest where id=${idx}`)
      if (!ret.rows || !ret.rows[0]) val = [null]
      else val = [parseInt(ret.rows[0].value)]
    } else {
      // Query all the 3 DBS in parallel
      ret = await Promise.all([
        connections["secondary1"][idx].query(`select value from synchtest where id=${idx}`),
        connections["secondary2"][idx].query(`select value from synchtest where id=${idx}`),
        connections["primary"][idx].query(`select value from synchtest where id=${idx}`)
      ])
      for (let j = 0; j < 3; j++) {
        if (!ret[j].rows || !ret[j].rows[0]) val[j] = null
        else val[j] = ret[j].rows[0].value
      }
    }
    val.map((v, i) => {
      if (v === null || v !== tm) {
        failures[i]++
        console.log(`select mismstach idx: ${idx} exp:${tm} val:${v} server:${i}`)
      } else {
        success++
      }
    })
  } catch (err) {
    console.log(err)
    process.exit(1)
  }
}

const create = async () => {
  let client = new Client(connectDetails.primary)
  try {
    let ret = await client.connect()
    ret = await client.query(`drop table if exists synchtest`)
    ret = await client.query(`create table if not exists synchtest (id int, value int)`)
    console.log(new Date(), `created the table synchtest`)
  } catch (err) {
    console.log(err)
    process.exit(1)
  }
}

const main = async () => {
  if (process.argv[2] === "create") {
    await create()
  } else if (process.argv[2] === "run") {
    console.log(new Date(), "creating new conns....")
    await doAllConnect()
    for (let loop = 0; loop < MAX_LOOPS; loop++) {
      console.log(new Date(), "deleting all data....")
      await connections["primary"][1].query("delete from synchtest")
      let promises = []
      for (let i = 1; i <= MAX_CONNECTIONS; i++) {
        promises.push(run(i))
      }
      await Promise.all(promises)
      console.log(new Date(), `completed waiting for all updates... ${promises.length}`)
      await sleep(1)
    }
    console.log(new Date(), "closing all conns....")
    await doAllClose("primary")
    if (!USE_RLB) {
      await doAllClose("secondary1")
      await doAllClose("secondary2")
    }
    console.log(`success: ${success} failures sec1: ${failures[0]} sec2:${failures[1]} leader:${failures[2]}`)
  } else {
    console.log(`Usage: ${process.argv[1]} create|run`)
    console.log(`                          create`)
    console.log(`                          run <num_connections> <num_loops>`)
  }
}

main()
  .then(() => process.exit(0))
  .catch(console.log)
