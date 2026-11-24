pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "loom-android"
include(
    ":app",
    ":core:common",
    ":presentation:designsystem", ":presentation:modes", ":presentation:home", ":presentation:chat",
    ":presentation:dashboard", ":presentation:settings", ":presentation:onboarding",
    ":presentation:panels",
    ":interaction:voice", ":interaction:edgelight", ":interaction:pointer",
    ":interaction:notifications",
    ":intelligence:llm", ":intelligence:planner", ":intelligence:memory",
    ":intelligence:skilllab", ":intelligence:selfheal", ":intelligence:authgate",
    ":execution:devicecontrol", ":execution:screenawareness", ":execution:tasks",
    ":execution:search", ":execution:messaging",
    ":execution:interaction",
    ":execution:videounderstanding", ":execution:devenv", ":execution:webresearch",
    ":execution:integrations", ":execution:shoppingskill",
    ":platform:services", ":platform:storage",
)
