package sh.paseo.glance.wear

internal fun shouldShowCompletedTurnMetadata(
  session: WatchSession?,
  activities: List<WatchActivity>,
  assistant: WatchMessage,
): Boolean {
  if (session == null || session.state == WatchState.WORKING) return false
  if (session.currentActivity?.state == WatchActivityState.RUNNING) return false
  if (activities.any { it.state == WatchActivityState.RUNNING }) return false
  if (activities.any { it.sequence > assistant.sequence }) return false
  return session.lastTurnDurationMs != null ||
    session.state == WatchState.COMPLETE ||
    session.state == WatchState.FAILED
}
