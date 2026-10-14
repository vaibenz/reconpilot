plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// ./gradlew :core:generateData  regenerates the labelled dataset in data/
tasks.register<JavaExec>("generateData") {
    group = "reconpilot"
    description = "Generate the seeded, labelled reconciliation dataset"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.vaibenz.reconpilot.eval.DatasetGenerator")
    workingDir = rootDir
    args("data", "42")
}

// ./gradlew :core:eval  scores rules vs LLM on data/ and writes eval/report.md
tasks.register<JavaExec>("eval") {
    group = "reconpilot"
    description = "Score the rules baseline against the LLM pipeline"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.vaibenz.reconpilot.eval.EvalMain")
    workingDir = rootDir
    args("data", "eval")
}
