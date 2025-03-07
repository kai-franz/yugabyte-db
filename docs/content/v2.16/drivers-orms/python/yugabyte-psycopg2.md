---
title: Connect an application
linkTitle: Connect an app
description: Python drivers for YSQL
image: /images/section_icons/sample-data/s_s1-sampledata-3x.png
menu:
  v2.16:
    identifier: yugabyte-psycopg2-driver
    parent: python-drivers
    weight: 400
type: docs
---

<ul class="nav nav-tabs-alt nav-tabs-yb">
  <li class="active">
    <a href="../yugabyte-psycopg2/" class="nav-link">
      YSQL
    </a>
  </li>
  <li>
    <a href="../ycql/" class="nav-link">
      YCQL
    </a>
  </li>
</ul>

<ul class="nav nav-tabs-alt nav-tabs-yb">
  <li >
    <a href="../yugabyte-psycopg2" class="nav-link active">
      <i class="icon-postgres" aria-hidden="true"></i>
      YugabyteDB Psycopg2 Smart Driver
    </a>
  </li>
  <li >
    <a href="../postgres-psycopg2" class="nav-link">
      <i class="icon-postgres" aria-hidden="true"></i>
      PostgreSQL Psycopg2 Driver
    </a>
  </li>
</ul>

The [Yugabyte Psycopg2 smart driver](https://github.com/yugabyte/psycopg2) is a distributed Python driver for [YSQL](../../../api/ysql/) built on the [PostgreSQL psycopg2 driver](https://github.com/psycopg/psycopg2), with additional [connection load balancing](../../smart-drivers/) features.

## CRUD operations

The following sections demonstrate how to perform common tasks required for Python application development using the YugabyteDB Psycopg2 smart driver.

To start building your application, make sure you have met the [prerequisites](../#prerequisites).

### Step 1: Add the YugabyteDB driver dependency

Building Psycopg2 requires a few prerequisites (a C compiler and some development packages). Check the [installation instructions](https://www.psycopg.org/docs/install.html#build-prerequisites) and [the FAQ](https://www.psycopg.org/docs/faq.html#faq-compile) for details.

The YugabyteDB Psycopg2 requires PostgreSQL version 12 or later (preferably 14).

After you've installed the prerequisites, install psycopg2-yugabytedb like any other Python package, using pip to download it from [PyPI](https://pypi.org/project/psycopg2-yugabytedb/):

```sh
$ pip install psycopg2-yugabytedb
```

Or, you can use the setup.py script if you've downloaded the source package locally:

```sh
$ python setup.py build
$ sudo python setup.py install
```

### Step 2: Set up the database connection

The following table describes the connection parameters required to connect, including [smart driver parameters](../../smart-drivers/) for uniform and topology load balancing.

| Parameter | Description | Default |
| :-------- | :---------- | :------ |
| host | Hostname of the YugabyteDB instance | localhost |
| port | Listen port for YSQL | 5433 |
| database/dbname | Database name | yugabyte |
| user | User connecting to the database | yugabyte |
| password | User password | yugabyte |
| `load_balance` | [Uniform load balancing](../../smart-drivers/#cluster-aware-connection-load-balancing) | Defaults to upstream driver behavior unless set to 'true' |
| `topology_keys` | [Topology-aware load balancing](../../smart-drivers/#topology-aware-connection-load-balancing) | If `load_balance` is true, uses uniform load balancing unless set to comma-separated geo-locations in the form `cloud.region.zone`. |

You can provide the connection details in one of the following ways:

- Connection string:

  ```python
  "dbname=database_name host=hostname port=port user=username password=password load_balance=true topology_keys=cloud.region.zone1,cloud.region.zone2"
  ```

- Connection dictionary:

  ```python
  user = 'username', password='xxx', host = 'hostname', port = 'port', dbname = 'database_name', load_balance='True', topology_keys='cloud.region.zone1,cloud.region.zone2'
  ```

The following is an example connection string for connecting to YugabyteDB.

```python
conn = psycopg2.connect(dbname='yugabyte',host='localhost',port='5433',user='yugabyte',password='yugabyte',load_balance='true')
```

#### Use SSL

The following table describes the connection parameters required to connect using SSL.

| Parameter | Description | Default |
| :-------- | :---------- | :------ |
| sslmode | SSL mode | prefer |
| sslrootcert | path to the root certificate on your computer | ~/.postgresql/ |

The following is an example for connecting to a YugabyteDB cluster with SSL enabled:

```python
conn = psycopg2.connect("host=<hostname> port=5433 dbname=yugabyte user=<username> password=<password> load_balance=true sslmode=verify-full sslrootcert=/path/to/root.crt")
```

If you created a cluster on [YugabyteDB Managed](https://www.yugabyte.com/cloud/), use the cluster credentials and [download the SSL Root certificate](../../../yugabyte-cloud/cloud-connect/connect-applications/).

### Step 3: Write your application

Create a new Python file called `QuickStartApp.py` in the base package directory of your project.

Copy the following sample code to set up tables and query the table contents. Replace the connection string `connString` with the cluster credentials and SSL certificate, if required.

```python
import psycopg2

# Create the database connection.

connString = "host=127.0.0.1 port=5433 dbname=yugabyte user=yugabyte password=yugabyte load_balance=True"

conn = psycopg2.connect(connString)

# Open a cursor to perform database operations.
# The default mode for psycopg2 is "autocommit=false".

conn.set_session(autocommit=True)
cur = conn.cursor()

# Create the table. (It might preexist.)

cur.execute(
  """
  DROP TABLE IF EXISTS employee
  """)

cur.execute(
  """
  CREATE TABLE employee (id int PRIMARY KEY,
                        name varchar,
                        age int,
                        language varchar)
  """)
print("Created table employee")
cur.close()

# Take advantage of ordinary, transactional behavior for DMLs.

conn.set_session(autocommit=False)
cur = conn.cursor()

# Insert a row.

cur.execute("INSERT INTO employee (id, name, age, language) VALUES (%s, %s, %s, %s)",
            (1, 'John', 35, 'Python'))
print("Inserted (id, name, age, language) = (1, 'John', 35, 'Python')")

# Query the row.

cur.execute("SELECT name, age, language FROM employee WHERE id = 1")
row = cur.fetchone()
print("Query returned: %s, %s, %s" % (row[0], row[1], row[2]))

# Commit and close down.

conn.commit()
cur.close()
conn.close()
```

Run the project `QuickStartApp.py` using the following command:

```python
python3 QuickStartApp.py
```

You should see output similar to the following:

```text
Created table employee
Inserted (id, name, age, language) = (1, 'John', 35, 'Python')
Query returned: John, 35, Python
```

If there is no output or you get an error, verify the parameters included in the connection string.

### Limitations

Currently, [PostgreSQL psycopg2 driver](https://github.com/psycopg/psycopg2) and [Yugabyte Psycopg2 smart driver](https://github.com/yugabyte/psycopg2) _cannot_ be used in the same environment.

## Learn more

- Refer to [YugabyteDB Psycopg2 driver reference](../../../reference/drivers/python/yugabyte-psycopg2-reference/) and [Try it out](../../../reference/drivers/python/yugabyte-psycopg2-reference/#try-it-out) for detailed smart driver examples.
- [YugabyteDB smart drivers for YSQL](../../smart-drivers/)
- Build Python applications using [PostgreSQL Psycopg2 smart driver](../postgres-psycopg2/)
- Build Python applications using [Django](../django/)
- Build Python applications using [SQLAlchemy](../sqlalchemy/)
