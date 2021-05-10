package scripts

package object s3wrapper {

  case class S3Bucket(name: String, region: String)

  case class S3RemoteFile(key: String, sizeInBytes: Long)

}
