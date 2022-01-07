

plugins {
    id("solidblocks.kotlin-application-conventions")
}

dependencies {

    implementation(project(":solidblocks-agent-base"))

    testImplementation("com.squareup.okhttp3:okhttp:4.9.3")

    testImplementation(project(":solidblocks-test"))
}

application {
    mainClass.set("de.solidblocks.helloworld.agent.CliKt")
}
