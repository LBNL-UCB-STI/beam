package census.db.creator.config

case class Config(db: DbConfig)
case class DbConfig(url: String, user: String, password: String)

object Hardcoded {
  val config = Config(DbConfig("jdbc:postgresql://127.0.0.1:5432/census", "postgres", "postgres1"))
}
