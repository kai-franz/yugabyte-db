---
title: Encryption at rest
linkTitle: Encryption at rest
description: YugabyteDB Managed cluster encryption at rest.
headcontent: Encrypt your YugabyteDB cluster
beta: /preview/faq/general/#what-is-the-definition-of-the-beta-feature-tag
menu:
  preview_yugabyte-cloud:
    identifier: managed-ear
    parent: cloud-secure-clusters
    weight: 460
type: docs
---

For added security, you can encrypt your clusters (including backups) using a customer managed key (CMK) residing in a cloud provider Key Management Service (KMS). You grant YugabyteDB Managed access to the key with the requisite permissions to perform cryptographic operations using the key to secure the databases in your clusters.

You can enable YugabyteDB EAR for a cluster as follows:

- On the **Security** page of the **Create Cluster** wizard when you [create your cluster](../../cloud-basics/create-clusters/).
- On the cluster **Settings** tab under **Encryption at rest** (database version 2.16.7 and later only).

Note that, regardless of whether you enable YugabyteDB EAR for a cluster, YugabyteDB Managed uses volume encryption for all data at rest, including your account data, your clusters, and their backups. Data is AES-256 encrypted using native cloud provider technologies - S3 and EBS volume encryption for AWS, Azure disk encryption, and server-side and persistent disk encryption for GCP. Volume encryption keys are managed by the cloud provider and anchored by hardware security appliances.

## Limitations

- You can't enable cluster EAR on clusters with YugabyteDB versions earlier than 2.16.7.
- Currently, Azure is not supported for CMKs.

Enabling EAR can impact cluster performance. You should monitor your workload after enabling this feature.

## Prerequisites

{{< tabpane text=true >}}

  {{% tab header="AWS" lang="aws" %}}

- Single-region [symmetric encryption key](https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html#symmetric-cmks) created in AWS KMS. The key resource policy should include the following [actions](https://docs.aws.amazon.com/kms/latest/developerguide/key-policy-default.html#key-policy-users-crypto):
  - kms:Encrypt
  - kms:Decrypt
  - kms:GenerateDataKeyWithoutPlaintext
  - kms:DescribeKey
  - kms:ListAliases
- Amazon Resource Name (ARN) of the CMK. For more information, refer to [Amazon Resource Names](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference-arns.html) in the AWS documentation.
- An access key for an [IAM identity](https://docs.aws.amazon.com/IAM/latest/UserGuide/id.html) with permission to encrypt and decrypt using the CMK. An access key consists of an access key ID and the secret access key. For more information, refer to [Managing access keys for IAM users](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html) in the AWS documentation.

For more information on AWS KMS, refer to [AWS Key Management Service](https://docs.aws.amazon.com/kms/) in the AWS documentation.

  {{% /tab %}}

  {{% tab header="GCP" lang="gcp" %}}

- CMK (AKA customer-managed encryption key or CMEK) created in Cloud KMS.
- Cloud KMS resource ID. You can copy the resource ID from KMS Management page in the Google Cloud console. Do not include the key version. For more information, refer to [Getting a Cloud KMS resource ID](https://cloud.google.com/kms/docs/getting-resource-ids) in the GCP documentation.
- A service account that has been granted the following permissions on the CMK:
  - cloudkms.keyRings.get
  - cloudkms.cryptoKeys.get
  - cloudkms.cryptoKeyVersions.useToEncrypt
  - cloudkms.cryptoKeyVersions.useToDecrypt
  - cloudkms.locations.generateRandomBytes
- Service account credentials. These credentials are used to authorize your use of the CMK. This is the key file (JSON) that you downloaded when creating credentials for the service account. For more information, refer to [Create credentials for a service account](https://developers.google.com/workspace/guides/create-credentials#create_credentials_for_a_service_account) in the GCP documentation.

For more information on GCP KMS, refer to [Cloud Key Management Service overview](https://cloud.google.com/kms/docs/key-management-service/) in the GCP documentation.

  {{% /tab %}}

{{< /tabpane >}}

## Encrypt a cluster using a CMK

You can enable EAR using a CMK for clusters in AWS and GCP (database version 2.16.7 and later only) as follows:

1. On the cluster **Settings** tab, select **Encryption at rest**.
1. Click **Enable Cluster Encryption at Rest**.
1. For AWS, provide the following details:

    - **Customer managed key (CMK)**: Enter the Amazon Resource Name (ARN) of the CMK to use to encrypt the cluster.
    - **Access key**: Provide an access key of an [IAM identity](https://docs.aws.amazon.com/IAM/latest/UserGuide/id.html) with permissions for the CMK. An access key consists of an access key ID and the secret access key.

    For GCP:
    - **Resource ID**: Enter the resource ID of the key ring where the CMK is stored.
    - **Service Account Credentials**: Click **Add Key** to select the credentials JSON file you downloaded when creating credentials for the service account that has permissions to encrypt and decrypt using the CMK.

1. Click **Save**.

YugabyteDB Managed validates the key and, if successful, starts encrypting the data. Only new data is encrypted with the new key. Old data remains unencrypted until compaction churn triggers a re-encryption with the new key.

To disable cluster EAR, click **Disable Encryption at Rest**. YugabyteDB Managed uses lazy decryption to decrypt the cluster.

## Rotate your CMK

{{< warning title="Deleting your CMK" >}}
If you delete a CMK, you will no longer be able to decrypt clusters encrypted using the key. Before deleting a CMK, make sure that you no longer need it. Retain all CMKs used to encrypt data in backups and snapshots.
{{< /warning >}}

To rotate the CMK used for EAR, do the following:

1. On the cluster **Settings** tab, select **Encryption at rest**.
1. Click **Edit CMK Configuration**.
1. For AWS, provide the following details:

    - **Customer managed key (CMK)**: Enter the Amazon Resource Name (ARN) of the new CMK to use to encrypt the cluster.
    - **Access key**: Provide an access key of an [IAM identity](https://docs.aws.amazon.com/IAM/latest/UserGuide/id.html) with permissions for the CMK. An access key consists of an access key ID and the secret access key.

    For GCP:
    - **Resource ID**: Enter the resource ID of the key ring where the new CMK is stored.
    - **Service Account Credentials**: Click **Add Key** to select the credentials JSON file you downloaded when creating credentials for the service account that has permissions to encrypt and decrypt using the CMK.

1. Click **Save**.

YugabyteDB Managed uses lazy decryption and encryption to encrypt the cluster using the new key.
