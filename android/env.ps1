# Points this PowerShell window at the Android tools in D:\Android.
# Nothing is changed system-wide; run it once per terminal:   . .\env.ps1
$env:JAVA_HOME         = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME      = 'D:\Android\sdk'
$env:ANDROID_SDK_ROOT  = 'D:\Android\sdk'
$env:ANDROID_USER_HOME = 'D:\Android\user'
$env:ANDROID_AVD_HOME  = 'D:\Android\avd'
$env:GRADLE_USER_HOME  = 'D:\Android\gradle'
$env:Path = "D:\Android\sdk\platform-tools;D:\Android\sdk\emulator;$env:Path"
