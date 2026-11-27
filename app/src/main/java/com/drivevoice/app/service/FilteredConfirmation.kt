package com.drivevoice.app.service

/** PCM evidence is sampled in order, in 10 ms frames. A recognizer's start event is advisory. */
internal class FilteredConfirmation {
    private var quietFrames = 0
    private var uncertainFrames = 0
    private var compromised = false
    var possibleSpeech = false
        private set

    fun observedText() { possibleSpeech = true }

    fun sample(frame: FrameEvidence) {
        if (frame.clipped) compromised = true
        when {
            frame.speechProbability >= .65f -> {
                possibleSpeech = true
                quietFrames = 0
            }
            frame.clipped || frame.speechProbability > .35f -> {
                quietFrames = 0
                uncertainFrames++
                if (uncertainFrames >= 10) possibleSpeech = true
            }
            else -> {
                uncertainFrames = 0
                quietFrames++
            }
        }
    }

    fun canCloseForSilence(elapsedMs: Long): Boolean =
        !possibleSpeech && !compromised && quietFrames >= 400 && elapsedMs >= 4_000

    fun maySend(quiet: Boolean, result: PcmRecognitionResult?, textObserved: Boolean): Boolean =
        quiet && !possibleSpeech && !compromised && !textObserved && result?.text == null &&
            result?.clean == true && result.endedAfterEof
}
