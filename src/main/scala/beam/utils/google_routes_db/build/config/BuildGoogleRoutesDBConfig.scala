package beam.utils.google_routes_db.build.config

case class BuildGoogleRoutesDBConfig(
  googleapiFiles: scala.List[BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm],
  postgresql: BuildGoogleRoutesDBConfig.Postgresql,
  spatial: BuildGoogleRoutesDBConfig.Spatial
)

object BuildGoogleRoutesDBConfig {

  case class GoogleapiFiles$Elm(
    http: scala.Option[BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Http],
    local: scala.Option[BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Local]
  )

  object GoogleapiFiles$Elm {

    case class Http(
      googleTravelTimeEstimationCsvFile: scala.Option[java.lang.String],
      googleapiResponsesJsonFile: scala.Option[java.lang.String]
    )

    object Http {

      def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Http = {
        BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Http(
          googleTravelTimeEstimationCsvFile =
            if (c.hasPathOrNull("googleTravelTimeEstimationCsvFile"))
              Some(c.getString("googleTravelTimeEstimationCsvFile"))
            else None,
          googleapiResponsesJsonFile =
            if (c.hasPathOrNull("googleapiResponsesJsonFile")) Some(c.getString("googleapiResponsesJsonFile")) else None
        )
      }
    }

    case class Local(
      googleTravelTimeEstimationCsvFile: scala.Option[java.lang.String],
      googleapiResponsesJsonFile: scala.Option[java.lang.String]
    )

    object Local {

      def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Local = {
        BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Local(
          googleTravelTimeEstimationCsvFile =
            if (c.hasPathOrNull("googleTravelTimeEstimationCsvFile"))
              Some(c.getString("googleTravelTimeEstimationCsvFile"))
            else None,
          googleapiResponsesJsonFile =
            if (c.hasPathOrNull("googleapiResponsesJsonFile")) Some(c.getString("googleapiResponsesJsonFile")) else None
        )
      }
    }

    def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm = {
      BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm(
        http =
          if (c.hasPathOrNull("http"))
            scala.Some(BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Http(c.getConfig("http")))
          else None,
        local =
          if (c.hasPathOrNull("local"))
            scala.Some(BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm.Local(c.getConfig("local")))
          else None
      )
    }
  }

  case class Postgresql(
    password: java.lang.String,
    url: java.lang.String,
    username: java.lang.String
  )

  object Postgresql {

    def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig.Postgresql = {
      BuildGoogleRoutesDBConfig.Postgresql(
        password = c.getString("password"),
        url = c.getString("url"),
        username = c.getString("username")
      )
    }
  }

  case class Spatial(
    localCRS: java.lang.String
  )

  object Spatial {

    def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig.Spatial = {
      BuildGoogleRoutesDBConfig.Spatial(
        localCRS = c.getString("localCRS")
      )
    }
  }

  def apply(c: com.typesafe.config.Config): BuildGoogleRoutesDBConfig = {
    BuildGoogleRoutesDBConfig(
      googleapiFiles = $_LBuildGoogleRoutesDBConfig_GoogleapiFiles$Elm(c.getList("googleapiFiles")),
      postgresql = BuildGoogleRoutesDBConfig.Postgresql(
        if (c.hasPathOrNull("postgresql")) c.getConfig("postgresql")
        else com.typesafe.config.ConfigFactory.parseString("postgresql{}")
      ),
      spatial = BuildGoogleRoutesDBConfig.Spatial(
        if (c.hasPathOrNull("spatial")) c.getConfig("spatial")
        else com.typesafe.config.ConfigFactory.parseString("spatial{}")
      )
    )
  }

  private def $_LBuildGoogleRoutesDBConfig_GoogleapiFiles$Elm(
    cl: com.typesafe.config.ConfigList
  ): scala.List[BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm] = {
    import scala.collection.JavaConverters._
    cl.asScala
      .map(
        cv => BuildGoogleRoutesDBConfig.GoogleapiFiles$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
      )
      .toList
  }
}
