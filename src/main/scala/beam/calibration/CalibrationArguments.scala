package beam.calibration

object CalibrationArguments {
  val BENCHMARK_EXPERIMENTS_TAG: String = "benchmark"
  val NUM_ITERATIONS_TAG: String = "num_iters"
  val EXPERIMENT_ID_TAG: String = "experiment_id"
  val NEW_EXPERIMENT_FLAG: String = "00000"
  val RUN_TYPE: String = "run_type"
  val SIGOPT_API_TOKEN_TAG: String = "sigopt_api_token"

  val RUN_TYPE_LOCAL: String = "local"
  val RUN_TYPE_REMOTE: String = "remote"
}
