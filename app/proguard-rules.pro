# 保留不使用混淆即可，模板默认配置
-keepattributes *Annotation*
-keep class com.workbuddy.quicklaunch.data.** { *; }

# Room 实体和 DAO 不被混淆
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# Kotlin 反射
-keep class kotlin.Metadata { *; }
