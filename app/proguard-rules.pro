# Minification is off (see app/build.gradle.kts), so nothing is required here.
# If it is ever switched on: kotlinx.serialization and OkHttp ship their own
# consumer rules; the Mistral DTOs under de.localvoice.mistralhandsfree.mistral
# are @Serializable and must not be renamed by hand-written rules.
