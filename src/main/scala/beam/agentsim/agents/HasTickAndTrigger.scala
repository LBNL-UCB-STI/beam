package beam.agentsim.agents

trait HasTickAndTrigger {
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Int] = None

  def holdTickAndTriggerId(tick: Int, triggerId: Long): Unit = {
    if (_currentTriggerId.isDefined || _currentTick.isDefined)
      throw new IllegalStateException(
        s"Expected both _currentTick and _currentTriggerId to be 'None' but found ${_currentTick} and ${_currentTriggerId} instead, respectively, while trying to hold ${tick} and ${triggerId}."
      )
    _currentTick = Some(tick)
    _currentTriggerId = Some(triggerId)
  }

  def releaseTickAndTriggerId(): (Int, Long) = {
    val theTuple = (_currentTick.get, _currentTriggerId.get)
    _currentTick = None
    _currentTriggerId = None
    theTuple
  }

  def getCurrentTick: Option[Int] = _currentTick

}
