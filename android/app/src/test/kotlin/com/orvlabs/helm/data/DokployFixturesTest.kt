package com.orvlabs.helm.data

import kotlinx.serialization.decodeFromString
import org.junit.Test

class DokployFixturesTest {
    private fun text(name: String) = javaClass.getResource("/dokploy/$name.json")!!.readText()

    @Test fun projects() { AppJson.decodeFromString<List<Project>>(text("project.all")) }
    @Test fun application() { AppJson.decodeFromString<Detail>(text("application.one")) }
    @Test fun compose() { AppJson.decodeFromString<Detail>(text("compose.one")) }
    @Test fun postgres() { AppJson.decodeFromString<Detail>(text("postgres.one")) }
    @Test fun deployments() { AppJson.decodeFromString<List<Deployment>>(text("deployment.all")) }
    @Test fun composeDeployments() { AppJson.decodeFromString<List<Deployment>>(text("deployment.allByCompose")) }
    @Test fun centralDeployments() { AppJson.decodeFromString<List<Deployment>>(text("deployment.allCentralized")) }
    @Test fun domains() { AppJson.decodeFromString<List<Domain>>(text("domain.byApplicationId")) }
    @Test fun destinations() { AppJson.decodeFromString<List<Destination>>(text("destination.all")) }
    @Test fun containers() { AppJson.decodeFromString<List<Container>>(text("docker.getContainers")) }
}
