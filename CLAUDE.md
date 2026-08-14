# Build

No system JDK; `JAVA_HOME` in the environment is invalid. Use the JBR bundled with Android Studio:

```
export JAVA_HOME=/home/michael/Devl/tools/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:compileDebugKotlin
```
