---
title: Back up data universe YCQL data
headerTitle: Back up universe YCQL data
linkTitle: Back up universe data
description: Use Yugabyte Platform to back up data in YCQL tables.
menu:
  v2.8_yugabyte-platform:
    parent: back-up-restore-universes
    identifier: back-up-universe-data-2-ycql
    weight: 20
type: docs
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li >
    <a href="{{< relref "./ysql.md" >}}" class="nav-link">
      <i class="icon-postgres" aria-hidden="true"></i>
      YSQL
    </a>
  </li>

  <li >
    <a href="{{< relref "./ycql.md" >}}" class="nav-link active">
      <i class="icon-cassandra" aria-hidden="true"></i>
      YCQL
    </a>
  </li>

</ul>

Use Yugabyte Platform to back up your YugabyteDB universe YCQL data.

To schedule backups for a later time, or as a recurring task, see [Schedule universe YCQL data backups](../../schedule-data-backups/ycql).

To immediately back up your YugabyteDB universe YCQL data:

1. Open the **Universe Overview** and then click the **Backups** tab. The **Backups** page appears.
1. Click **Create Backup** to open the **Create Backup** dialog.

    <br/><br/>

    ![Create Backup - YCQL](/images/yp/create-backup-ycql.png)

1. Click the **YCQL** tab and enter the following:

    - **Storage**: Select the storage type: `GCS Storage`, `S3 Storage`, or `NFS Storage`.
    - **Namespace**: Select the namespace from the drop-down list of available namespaces.
    - **Parallel Threads**: Enter or select the number of threads. The default is `8`.

1. Click **OK**. The requested backup begins immediately.

{{< note title="Note" >}}

If the universe has [encryption at rest enabled](../../../security/enable-encryption-at-rest), data files are backed up as-is (encrypted) to reduce the computation cost of a backup and to keep the files encrypted. A universe key metadata file, containing key references, is also backed up.

{{< /note >}}
