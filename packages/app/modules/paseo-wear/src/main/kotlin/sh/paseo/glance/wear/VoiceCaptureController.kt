package sh.paseo.glance.wear

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe control seam between the Compose actions and blocking AudioRecord capture. */
internal class VoiceCaptureController {
  enum class State { RECORDING, FINISHING, CANCELED }

  private val state = AtomicReference(State.RECORDING)
  private val stopRecorder = AtomicReference<(() -> Unit)?>(null)

  fun state(): State = state.get()

  fun attachRecorderStop(stop: () -> Unit) {
    stopRecorder.set(stop)
    if (state.get() != State.RECORDING) stopOnce()
  }

  fun detachRecorderStop() {
    stopRecorder.set(null)
  }

  /** Returns true only for the first Finish tap. */
  fun finish(): Boolean {
    if (!state.compareAndSet(State.RECORDING, State.FINISHING)) return false
    stopOnce()
    return true
  }

  /** Cancel is intentionally distinct from Finish and never asks the caller to upload. */
  fun cancel(): Boolean {
    val previous = state.getAndSet(State.CANCELED)
    if (previous == State.CANCELED) return false
    stopOnce()
    return true
  }

  private fun stopOnce() {
    stopRecorder.getAndSet(null)?.invoke()
  }
}

/** Invalidates stale capture coroutines before their blocking recorder is interrupted. */
internal class VoiceCaptureGeneration {
  private val generation = AtomicLong(0)

  fun next(): Long = generation.incrementAndGet()
  fun invalidate(): Long = generation.incrementAndGet()
  fun isCurrent(value: Long): Boolean = generation.get() == value
}
