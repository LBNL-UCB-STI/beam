package census.db.creator.config

case class Config(db: DbConfig, censusUrl: String, workingDir: String)
case class DbConfig(url: String, user: String, password: String)

object Hardcoded {

  val config = Config(
    db = DbConfig("jdbc:postgresql://127.0.0.1:5432/census", "postgres", "postgres1"),
    censusUrl = "https://www2.census.gov/geo/tiger/TIGER2019/TRACT/",
    workingDir = "/tmp/census"
  )
}
