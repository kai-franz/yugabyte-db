---
title: Back Up and Restore Yugabyte Platform on Kubernetes
headerTitle: Back Up and Restore Yugabyte Platform on Kubernetes
description: Use a script file to back up and restore Yugabyte Platform on Kubernetes.
linkTitle: Back Up and Restore Yugabyte Platform
menu:
  v2.8_yugabyte-platform:
    identifier: back-up-restore-k8s
    parent: administer-yugabyte-platform
    weight: 20
type: docs
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li >
    <a href="../back-up-restore-yp" class="nav-link">
      <i class="fa-solid fa-cloud"></i>
      Default
    </a>
  </li>

  <li>
    <a href="../back-up-restore-k8s" class="nav-link active">
      <i class="fa-solid fa-cubes" aria-hidden="true"></i>
      Kubernetes
    </a>
  </li>

</ul>

Yugabyte Platform installations include configuration settings, certificates and keys, and other components required for orchestrating and managing YugabyteDB universes.

You can use the Yugabyte Platform backup script to back up an existing Yugabyte Platform server and restore it, when needed, for disaster recovery or migrating to a new server.

{{< note title="Note" >}}

You cannot back up and restore Prometheus metrics data.

{{< /note >}}

## Download the script

Download the version of the backup script that corresponds to the version of Yugabyte Platform that you are backing up and restoring.

For example, if you are running version {{< yb-version version="v2.8">}}, you can copy the `yb_platform_backup.sh` script from the `yugabyte-db` repository using the following `wget` command:

```sh
wget https://raw.githubusercontent.com/yugabyte/yugabyte-db/v{{< yb-version version="v2.8">}}/managed/devops/bin/yb_platform_backup.sh
```

If you are running a different version of Yugabyte Platform, replace the version number in the command with the correct version number.

## Back Up a Yugabyte Platform Server

You can back up the Yugabyte Platform server as follows:

- Verify that the computer performing the backup operation can access the Yugabyte Platform Kubernetes pod instance by executing the following command:

  ```sh
  kubectl exec --namespace <k8s_namespace> -it <k8s_pod> -c yugaware -- cat /opt/yugabyte/yugaware/README.md
  ```

  *k8s_namespace* specifies the Kubernetes namespace where the Yugabyte Platform pod is running.
  *k8s_pod* specifies the name of the Yugabyte Platform Kubernetes pod.

- Run the `yb_platform_backup.sh` script using the `create` command, as follows:

  ```sh
  ./yb_platform_backup.sh create --output <output_path> --k8s_namespace <k8s_namespace> --k8s_pod <k8s_pod> [--exclude_releases --verbose]
  ```

  *backup* is the command to run the backup of the Yugabyte Platform server.

  *output_path* specifies the location for the output backup archive.

  *k8s_namespace* specifies the Kubernetes namespace in which the Yugabyte Platform pod is running.

  *k8s_pod* specifies the name of the Yugabyte Platform Kubernetes pod.

  *exclude_releases* excludes Yugabyte releases from the backup archive.

  *verbose* prints debug output.

- Verify that the backup `.tar.gz` file, with the correct timestamp, is in the specified output directory.

- Upload the backup file to your preferred storage location, and delete it from the local disk.

## Restore a Yugabyte Platform Server

To restore the Yugabyte Platform content from your saved backup, perform the following:

- Execute the following command to verify that the computer performing the backup operation can access the Yugabyte Platform Kubernetes pod instance:

    ```sh
    kubectl exec --namespace <k8s_namespace> -it <k8s_pod> -c yugaware -- cat /opt/yugabyte/yugaware/README.md
    ```

    *k8s_namespace* specifies the Kubernetes namespace where the Yugabyte Platform pod is running.

    *k8s_pod* specifies the name of the Yugabyte Platform Kubernetes pod.

- Run the `yb_platform_backup.sh` script using the `restore` command:

    ```sh
    ./yb_platform_backup.sh restore --input <input_path> --k8s_namespace <k8s_namespace> --k8s_pod <k8s_pod> [--verbose]
    ```

    *restore* restores the Yugabyte Platform server content.

    *input_path* is the path to the `.tar.gz` backup file to restore.

    *k8s_namespace* specifies the Kubernetes namespace where the Yugabyte Platform pod is running.

    *k8s_pod* specifies the name of the Yugabyte Platform Kubernetes pod.

    *verbose* prints debug output.

Upon completion of the preceding steps, the restored Yugabyte Platform is ready to continue orchestrating and managing your universes and clusters.
