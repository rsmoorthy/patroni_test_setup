
postgres: synch_commit_test
--------------------------------------------------------------------

This is a fork of `Patroni <https://github.com/zalando/patroni>`_, just used to setup patroni and 3 node cluster. This cluster is used to do the setup of synch_commit_test

Follow these steps in good faith to get the cluster up and run the synchronous commit testing

::
   $ git clone https://github.com/rsmoorthy/patroni
   $ cd patroni/docker
   $ docker build -t synch_commit_test -f Dockerfile.synch_commit_test .
   $ cd ..
   $ docker build -t patroni .
   $ docker-compose up -d

At this point of time, the cluster should be up. If there are any issues in bringing up the cluster, `please follow here <https://github.com/rsmoorthy/patroni/blob/master/docker/README.md>`_

After the cluster is up, we need to do two things.

1. Enable synchronous_commit to **remote_apply**
2. Set **patroni1** as the leader/primary (just to make it easier for the scripts to run)

============
Enable synchronous_commit and fsync
============

::

   $ docker exec -it demo-patroni1 bash
   # Check the list of members.
   $ patronictl list
   # Edit the configuration to make synchronous_commit to remote_apply and fsync to on
   $ patronictl edit-config -q --force -p synchronous_commit=remote_apply
   $ patronictl edit-config -q --force -p fsync=on
   # Just to be sure, restart
   $ patronictl restart
   # Verify if synchronous_commit is really set to remote_apply
   $ PGPASSWORD=postgres psql -U postgres -h patroni1 postgres -c "select name,setting from pg_settings where name='synchronous_commit'"


============
Set patroni1 as the leader
============

::

   $ docker exec -it demo-patroni1 bash
   $ patronictl list ## <-- if this shows some other node other than patroni1 as leader
   $ patronictl switchover  ## <-- make the patroni1 as leader
   # Wait for few secs
   $ patronictl list ## <-- verify if patroni1 is the leader


============
Run the synchronous_commit tests
============

::

   $ docker exec -it synch_commit_test bash
   # cd /code/node
   # node synch_commit_test.js create
   <.... message saying created table ...>

   ## Now run the tests. You can start with small connections and loop count as 5, then increase it
   # node synch_commit_test.js run 30 5
   # node synch_commit_test.js run 30 500

The tests will show success and failed outcomes. 
