# Keep line numbers in crash reports, but hide the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Libraries used here (MapLibre, Play Services, Compose, DataStore) ship their own rules.
