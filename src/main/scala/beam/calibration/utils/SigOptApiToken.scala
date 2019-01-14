package beam.calibration.utils

import com.sigopt.exception.APIConnectionError

object SigOptApiToken {

  def getClientAPIToken: String = Option { System.getenv("SIGOPT_API_TOKEN") }.getOrElse(
    throw new APIConnectionError(
      "Correct developer client token must be present in environment as SIGOPT_DEV_API Token"
    )
  )

}
