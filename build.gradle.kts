plugins {
    java
    `maven-publish`
}

group = "net.gahvila"
version = "0.17.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.gahvila.net/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.aikar.co/content/groups/aikar/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.popcraft:chunky-common:1.4.49")
    compileOnly("org.popcraft:chunkyborder-common:1.2.33")
    compileOnly("org.popcraft:chunkyborder-bukkit:1.2.33")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    //claim plugins
    compileOnly("net.crashcraft:crashclaim:1.0.42")
    compileOnly("com.github.TechFortress:GriefPrevention:16.18.4")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.14")

}

publishing {
    repositories {
        maven {
            name = "gahvila"
            url = if (version.toString().contains("SNAPSHOT", ignoreCase = true)) {
                uri("https://repo.gahvila.net/snapshots/")
            } else {
                uri("https://repo.gahvila.net/releases/")
            }
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.gahvila"
            artifactId = "rtp"
            version = findProperty("version").toString()
            from(components["java"])
        }
    }
}

tasks {
    processResources {
        expand(project.properties)
    }
}
