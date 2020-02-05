package census.db.creator.database
import census.db.creator.config.Config
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion

class DbMigrationHandler(private val config: Config) {

  def handle(): Unit = {
    val flyway = new Flyway
    flyway.setDataSource(config.db.url, config.db.user, config.db.password)
    flyway.setBaselineVersion(MigrationVersion.fromVersion("0.0"))
    flyway.setBaselineOnMigrate(true)
    flyway.setLocations("census_db")
    flyway.migrate()
  }

}
