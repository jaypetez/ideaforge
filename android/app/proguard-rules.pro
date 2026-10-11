# R8 rules for release builds.
#
# Deliberately empty. kotlinx.serialization and OkHttp ship their own consumer rules inside
# their jars (META-INF/com.android.tools/r8/ and META-INF/proguard/), and the app keeps no
# class by reflection. A minified release build signed with a throwaway key was installed on an
# emulator (#95), opened onboarding, listed a local server's models over OkHttp, saved the
# keyring, opened Settings and ran a real model turn with no crash, so no rule was needed.
#
# Add a rule only for a crash you have reproduced in a release build, and say here why it
# exists. A broad -keep makes the APK bigger and hides the next real problem.
