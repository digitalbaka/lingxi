-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# R8 会重写/裁剪泛型签名。Retrofit 的 suspend 方法靠 Continuation<? super T> 的泛型参数
# 推导返回类型，签名一旦被裁成裸类型（Lkotlin/coroutines/Continuation;），
# HttpServiceMethod 里 (ParameterizedType) continuationType 就会抛 ClassCastException，
# 请求在建 socket 之前就失败（登录、首页全部接口受影响）
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep class com.lingxi.classic.data.api.ApiService { *; }

# R8 full mode 会把 suspend 方法参数里的 Continuation<? super T> 简化成裸类型（类型实参丢失）。
# 保留 Continuation 自身（不混淆）后，签名里的类型实参才会原样保留
-keep class kotlin.coroutines.Continuation { *; }

# Gson 的 TypeToken 匿名子类靠父类泛型签名解析实际类型。签名被裁掉后这些子类会被 R8 合并成一个，
# 运行时报 "TypeToken must be created with a type argument"，令牌刷新/首页缓存/上传解析都会失败
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Gson 反射解析的数据模型
-keep class com.lingxi.classic.data.model.** { *; }

-dontwarn com.google.errorprone.annotations.*
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
