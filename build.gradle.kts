// Plugins declarados aqui e aplicados no módulo :app.
// AGP 9 já traz suporte embutido a Kotlin (não é preciso o plugin org.jetbrains.kotlin.android).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
