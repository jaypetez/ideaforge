# SpeechRecognizer on the emulator (#89, emulator half)

Throwaway spike notes; never packaged (`tools/assemble-site.mjs` publishes only `src`, `guide`,
`docs/screenshots` and four root files). The phone half is `PHONE-CHECKLIST.md`. Raw logs are in
`logs/`. Times are ms since the trial started (`elapsedRealtimeNanos`); `s1`, `s2`… number each
`startListening` call.

## Device

- AVD `Medium_Phone_API_36.0` (`sdk_gphone64_x86_64`), Android 16 / API 36 (`BP22.250325.006`), `hw.audioInput=yes`.
- `isRecognitionAvailable` and `isOnDeviceRecognitionAvailable` both true.
- Default recognizer: `com.google.android.tts/…GoogleTTSRecognitionService` (`google-speech-apk_20260817.01_p0.966249458`).
- On-device recognizer: `com.google.android.as/…AiAiSpeechRecognitionService` (`B.0.playstore.pixel6.738427685`).
- The system audio log shows the microphone held by the recognizer's own package, never by our app.
- `checkRecognitionSupport`: on-device reports en-US installed. Default answers one call with
  `onError CANNOT_CHECK_SUPPORT` and then two `onSupportResult`; its installed list went from empty
  to `[en-US]` after the first on-device session.
- Limits: nobody spoke into the emulator microphone (it heard low noise, −2…+2 dB). Real speech went
  in through `EXTRA_AUDIO_SOURCE`: a TTS clip at 24 kHz fed in real time, with 0.8 s of silence before
  and 4 s after. That path does not exercise the microphone.

## Assumptions in #91 (native capture and press-to-talk)

| Assumption | Emulator result |
|---|---|
| Use on-device when available | **Confirmed, with a caveat.** It is a different service that answers differently: finals carry confidence 0.000 (default ≈0.97), it never fills `UNSTABLE_TEXT`, it capitalises and repeats partials, and a cold first ready takes 1.5–1.9 s (default 0.7–1.4 s). |
| Partial results arrive | **Confirmed** (audio-source path). Default splits each partial: settled words in `RESULTS_RECOGNITION`, the rest in `android.speech.extra.UNSTABLE_TEXT`; early partials are `""` with unstable text "we went over". With `PREFER_OFFLINE` the default recognizer behaved like on-device and sent partials even with `PARTIAL_RESULTS=false`. |
| Sessions end on their own; capture restarts and joins segments | **Restart confirmed; joining real speech across a restart not testable.** In silence every session ends with `ERROR_NO_MATCH` ≈5.0 s after ready, on both recognizers. `startListening` again on the same recognizer, inside the callback, is ready in 45–150 ms; no BUSY across 60+ restarts. **Destroying and recreating inside the callback fails within 50 ms with `ERROR_SERVER_DISCONNECTED`.** |
| Segmented sessions could replace restarts | **Partly contradicted.** Over the audio source both send `onSegmentResults`, then `onEndOfSegmentedSession` when input closes. On the mic with a 20 s minimum length, silence still ended the session with NO_MATCH (5 s default, 14 s on-device, with a spurious `onBeginningOfSpeech` and a doubled `onEndOfSpeech`). |
| Meter from `onRmsChanged` only | **Confirmed.** Starts 30–750 ms after ready, ≈ every 20 ms for the whole session; floor exactly −2.0, peaks ≈ +10. |
| no-match / timeout → miss | **Confirmed, different code.** Silence gives `ERROR_NO_MATCH`; `ERROR_SPEECH_TIMEOUT` never appeared. `stopListening` with no speech gives NO_MATCH 100–160 ms later. |
| permission → fatal denied | **Confirmed.** `ERROR_INSUFFICIENT_PERMISSIONS` 1.6–2.3 s after `startListening`, no ready first. |
| busy → recoverable | Not testable (BUSY never occurred). |
| *(new)* `stopListening` returns what was said | **Contradicted for on-device.** Default answers a mid-sentence stop within ≈300 ms with a final that includes the unstable words ("we went over budget"@0.972). **On-device answers `ERROR_CLIENT` with no final and the words are lost (2 of 2).** Press-to-talk release and Pause must keep the last partial themselves and must not treat that `ERROR_CLIENT` as fatal. |
| "we went over budget" must not end the answer; terminal "…over" must, after settleMs | **Confirmed that a settle timer is needed.** Mid-sentence, a partial ends in "over" for 130–700 ms (default: unstable "we went over" → stable "we went over" + unstable " budget last"; on-device repeats "We went over" over 130 ms). For "…like Tetris over" the final arrives 300–500 ms after the last partial, after `onEndOfSpeech`. |
| "Settled" means confidence > 0 (the web rule) | **Contradicted for on-device** (finals report 0.000). Finals are marked by `final_result=true`, or by arriving in `onResults` / `onSegmentResults`. |
| firstWordMs is not reset by restarts | **Confirmed in shape.** The engine is never silent: it ends a session ≈ every 5.1 s and restarts cleanly, as CLAUDE.md describes for Android Chrome. |
| Never open a second AudioRecord | Not testable. |
| *(new)* Where the final arrives | Both recognizers delivered the final hypotheses in **`onPartialResults` with `final_result=true`**, right after `onEndOfSpeech`. `onResults` came only when the input closed, 3.5–4 s later, and was empty. |

## Assumptions in #92 (voice stage and hands-free as a foreground service)

| Assumption | Emulator result |
|---|---|
| A microphone foreground service keeps recognition hearing with the screen off | **Confirmed.** Service on, no wake lock, 25 s screen-off: live levels kept arriving (61 windows) and the audio log said "not silenced". |
| *(control)* Without the service | **Recognition goes deaf with no error.** The session running at screen-off kept its audio until it ended; every session started after screen-off was marked *silenced* ≈0.5 s in, level pinned at −2.0 with two level callbacks per 5 s session, each ending in an ordinary NO_MATCH while restarts continued. Audio returned at screen-on. |
| Missing levels are reported | **Confirmed, and this is the shape.** A silenced capture still sends level callbacks, pinned to the floor: "no level above −2.0 for a whole session" is the deafness signal, not "no callbacks". |
| The service can start from the visible app | **Confirmed** on API 36 with the permission held and the activity visible; the system shows it as foreground type microphone. Notification actions not built in this slice. |
| Pause / Exit keep the draft | **Partly contradicted.** Default flushes a final on stop; on-device discards the utterance with `ERROR_CLIENT`. `cancel()` is immediate. |

## Raw callback timelines (verbatim excerpts)

```
# default, mic, silence, immediate restarts (01)
   122.4 s1 startListening(LANGUAGE=en-US, …PARTIAL_RESULTS=true, PREFER_OFFLINE=false…)
   789.0 s1 onReadyForSpeech {}
  5834.7 s1 onError ERROR_NO_MATCH
  5836.6 s1 restart #1: startListening again, immediately, inside the onError callback
  5908.0 s2 onReadyForSpeech {}
 10967.9 s2 onError ERROR_NO_MATCH
# default, audio source, "we went over budget last year" (01)
  9656.1 s1 onBeginningOfSpeech
 10471.5 s1 onPartialResults [""@n/a] extra{android.speech.extra.UNSTABLE_TEXT=[we went over]}
 11155.5 s1 onPartialResults ["we went over"@n/a] extra{…UNSTABLE_TEXT=[ budget last]}
 12532.1 s1 onEndOfSpeech
 12546.0 s1 onPartialResults ["we went over budget last year"@0.972 | "we went over budgeted last year"@0.874] extra{final_result=true}
 16251.4 s1 onResults texts=<none>
# on-device, same phrase (02)
  4465.0 s1 onPartialResults ["We went over"@n/a]
  4599.6 s1 onPartialResults ["We went over"@n/a]
  4731.0 s1 onPartialResults ["We went over bud"@n/a]
  5960.6 s1 onEndOfSpeech
  6035.9 s1 onPartialResults ["We went over budget last year"@0.000 | "We went over Budget last year"@0.000] extra{final_result=true}
# "…like Tetris over", default (02)
  5590.1 s1 onPartialResults ["I want to make a game like Tetris"@n/a] extra{UNSTABLE_TEXT=[ over]}
  5889.9 s1 onPartialResults ["I want to make a game like Tetris over"@0.968 | …] extra{final_result=true}
# restart inside onResults, "skip this" (02)
  8112.2 s1 onResults texts=<none>
  8179.9 s2 onReadyForSpeech {}
 10924.1 s2 onPartialResults ["skip this"@0.944 | "get this"@0.745 | "escape this"@0.689] extra{final_result=true}
# segmented, audio source (03)
  6155.4 s1 onSegmentResults ["we went over budget last year"@0.972 | …]
  9707.3 s1 onEndOfSegmentedSession
# recreate inside the callback (03)
  6591.4 s1 destroying the previous recognizer before restart
  6594.7 s2 startListening(…)
  6644.2 s2 onError ERROR_SERVER_DISCONNECTED
# stopListening mid-utterance: default, then on-device (04)
  4742.1 s1 user: stopListening()
  5052.4 s1 onPartialResults ["we went over budget"@0.972 | …] extra{final_result=true}
  ---
  4608.8 s1 user: stopListening()
  4701.6 s1 onError ERROR_CLIENT
# permission revoked
  2590.3 s1 onError ERROR_INSUFFICIENT_PERMISSIONS
# screen off without the foreground service (07)
  7457.1 -- broadcast SCREEN_OFF
 11785.6 s3 first onRmsChanged rmsdB=-2.0
 16799.4 s3 onRmsChanged x1 min=-2.00 max=-2.00 last=-2.00
 16801.6 s3 onError ERROR_NO_MATCH
# system audio log (dumpsys audio)
12:03:34:388 rec update src:VOICE_RECOGNITION silenced pack:com.google.android.tts
12:03:53:938 rec update src:VOICE_RECOGNITION not silenced pack:com.google.android.tts
```

## What #91 and #92 should take from this

1. Read finals from `onPartialResults` when `final_result=true`, as well as from `onResults` and `onSegmentResults`. An empty `onResults` means no new text, not a miss.
2. Build the caption and the trigger check from the settled text plus `UNSTABLE_TEXT`.
3. Never judge "settled" by confidence on Android.
4. Restart on the same recognizer; never destroy and recreate inside a callback.
5. Keep the latest partial as the draft: on-device `stopListening` discards the utterance (`ERROR_CLIENT`).
6. Map `ERROR_NO_MATCH` to a miss, and expect one about every 5 s of silence.
7. Run hands-free under the microphone foreground service, and report a session whose level never leaves −2.0 as deaf.
