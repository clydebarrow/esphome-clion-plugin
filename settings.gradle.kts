rootProject.name = "esphome-clion-plugin"

// Repositories are declared per-module (catalog needs only Maven Central; the
// plugin additionally needs the IntelliJ Platform repositories). This keeps the
// fast :catalog module decoupled from the IntelliJ SDK tooling.
include(":catalog", ":plugin")
