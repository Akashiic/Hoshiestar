<div align="center">
  <h1 align="center">Youer 26.1.2</h1>
  <h3 align="center">Minecraft NeoForge Hybrid server implementing the Paper/Purpur API</h3>

  <p align="center">
    <a href="https://github.com/MohistMC/Youer/stargazers">
      <img alt="GitHub Stars" src="https://img.shields.io/github/stars/MohistMC/Youer?logo=github&color=181717&style=flat-square">
    </a>
    <a href="https://neoforged.net/">
      <img alt="NeoForge" src="https://img.shields.io/badge/NeoForge-26.1.2.122-FF8B00?style=flat-square">
    </a>
    <a href="https://www.azul.com/downloads/?version=java-25-lts#zulu">
      <img alt="JDK" src="https://img.shields.io/badge/JDK-25.0.1-007396?logo=java&logoColor=white&style=flat-square">
    </a>
    <a href="https://docs.gradle.org/9.7.0/release-notes.html">
      <img alt="Gradle" src="https://img.shields.io/badge/Gradle-9.7.0-02303A?logo=gradle&logoColor=white&style=flat-square">
    </a>
    <a href="https://discord.gg/mohistmc">
      <img alt="Discord" src="https://img.shields.io/discord/311256119005937665?color=5865F2&logo=discord&logoColor=white&style=flat-square">
    </a>
  </p>
</div>

## 🚀 Features

- Hybrid server combining NeoForge mod support with Paper API compatibility
- Seamless plugin support from Bukkit/Spigot/Paper ecosystem
- Optimized performance and stability for modded environments

## 📊 Progress Status

✅ **Core Integration**
- [x] NeoForge 26.1.2.122 ([02fa4bbcb](https://github.com/neoforged/NeoForge/commit/02fa4bbcb))
- [x] Paper 26.1.2 ([e4e17fc90](https://github.com/PaperMC/Paper/commit/e4e17fc90), build #74)
- [x] PurPur 26.1.2 (PurpurMC `ver/26.1.2`)

> **Port note:** the Paper/Purpur integration of this branch was backported from the
> Youer `26.2` branch: Paper/Purpur API and CraftBukkit sources come from the official
> `ver/26.1.2` branches of PaperMC/PurpurMC, and the Minecraft source patches from
> Youer 26.2 adapted to Minecraft 26.1.2. Content that only exists in 26.2
> (sulfur cube, potent sulfur, speleothem) is not included.

🔄 **Compatible Progress**

## 📚 Documentation

- [English Documentation](https://mohistmc.com/youer/docs)
- [中文文档](https://www.mohistmc.cn/docs/youer)

## ⚙️ Technical Stack

| Component   | Source                                                              | Purpose                      | Status |
|-------------|---------------------------------------------------------------------|------------------------------|--------|
| NeoForge    | [GitHub](https://github.com/neoforged/NeoForge.git)                 | Mod support                  | ✅      |
| Paper       | [GitHub](https://github.com/PaperMC/Paper.git)                      | Plugin support               | ✅      |
| PurPur      | [GitHub](https://github.com/PurpurMC/Purpur.git)                    | Plugin support               | ✅      |