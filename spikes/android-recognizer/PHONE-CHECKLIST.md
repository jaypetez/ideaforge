# Recognizer probe: phone checklist (#89, about 10 minutes)

The emulator half is in `FINDINGS-emulator.md`. This run answers what the emulator could not:
real-microphone speech, restarts across speech, BUSY, and screen-off on real hardware.

1. **Connect.** Turn on USB debugging and check that `adb devices` lists the phone. Note whether the Google app is installed.
2. **Install.** `cd android && ./gradlew :app:assembleDebug` (`gradlew.bat` on Windows), then
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The debug APK artifact from CI's `android` job works too.
3. **Start a clean log.** `adb shell rm -f /sdcard/Android/data/io.github.jaypetez.ideaforge.debug/files/recognizer-probe.log`
4. **Launch the probe.**
   `adb shell am start -n io.github.jaypetez.ideaforge.debug/io.github.jaypetez.ideaforge.MainActivity --es probe recognizer`.
   Choose **While using the app** for the microphone and **Allow** for notifications, then tap **Report**.
5. **Default recognizer, trigger word mid-sentence.** Leave the defaults (Partials on, Restarts 0, Audio: Mic).
   Tap **Start trial** and say "**we went over budget last year**". Wait for `trial … finished` in the log.
6. **Default recognizer, trigger word at the end.** Start again and say "**I want to make a game like Tetris over**".
7. **On-device.** Turn on **On-device** and repeat steps 5 and 6.
8. **Stop mid-sentence.** Keep On-device on. Start, say "**we need a website for the bakery and it should be cheap**",
   and tap **Stop** halfway through. Turn On-device off and do it again. (On the emulator, on-device lost the words.)
9. **Restarts across speech.** Restarts **3**, On-device off. Start, say "**it should be fast**", pause 3 s,
   say "**it should be cheap**", then stay quiet until it finishes.
10. **Silence, then speech.** Restarts **Until stop**. Start, stay silent 15 s, say "**repeat customers matter over**",
    wait 3 s, tap **Stop**.
11. **Segmented session.** Turn on **Segmented**, Restarts 0, and say two sentences with a 3 s gap.
12. **Screen off with the foreground service.** Turn on **Mic FGS** and **Delay 10 s**, Restarts **Until stop**,
    Segmented off. Tap **Start trial** and press the power button within 10 s. Over about 30 s with the screen off,
    say "**we went over budget last year**" twice. Wake, unlock, tap **Stop**.
13. **Screen off without the service.** Repeat step 12 with **Mic FGS off**. If there is time, once more with **Wake lock** on.
14. **Send the log back.** Either
    `adb pull /sdcard/Android/data/io.github.jaypetez.ideaforge.debug/files/recognizer-probe.log` and attach it to #89,
    or tap **Share** and paste it into a comment on #89 (Share sends the whole file, trimmed to the last 200 KB).
    The log already records the phone model, Android version and recognizer packages.
