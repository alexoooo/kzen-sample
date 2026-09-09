package tech.kzen.sample.embed.client

import tech.kzen.sample.embed.client.codegen.SampleEmbedJsModule

fun main() {
    SampleEmbedJsModule.register()
    tech.kzen.auto.client.main()
}
