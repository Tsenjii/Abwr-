package dev.local.aibrowser

/** Presentation groups within a cookie container. Moving a tab never changes its session. */
internal class TabGroups(initialNextId: Long = 1L) {
    data class Group(val id: String, val profile: String, var name: String, var color: Int,
        val tabs: LinkedHashSet<String> = linkedSetOf())
    private val entries = linkedMapOf<String, Group>()
    private var nextId = initialNextId.coerceIn(1L, Long.MAX_VALUE - 18000L)
    val nextSequence: Long get() = nextId
    fun all(profile: String) = entries.values.filter { it.profile == profile }
    fun group(profile: String, id: String) = entries[id]?.takeIf { it.profile == profile }
        ?: throw IllegalArgumentException("Unknown tab group")
    fun membership(profile: String, tab: String) = all(profile).firstOrNull { tab in it.tabs }?.id
    fun create(profile: String, name: String, color: Int, tabs: Collection<String>, valid: Set<String>): Group {
        require(all(profile).size < 60) { "Maximum 60 groups per container" }
        validate(name, color)
        require(tabs.all { it in valid && it != "popup" }) { "Unknown or temporary tab" }
        val item = Group("group${nextId++}", profile, name.trim(), color)
        entries[item.id] = item
        move(profile, tabs, item.id, valid)
        return item
    }
    fun rename(profile: String, id: String, name: String, color: Int) {
        validate(name, color)
        group(profile, id).apply { this.name = name.trim(); this.color = color }
    }
    fun move(profile: String, tabs: Collection<String>, destination: String?, valid: Set<String>) {
        require(tabs.all { it in valid && it != "popup" }) { "Unknown or temporary tab" }
        val target = destination?.let { group(profile, it) }
        all(profile).forEach { it.tabs.removeAll(tabs.toSet()) }
        target?.tabs?.addAll(tabs)
    }
    fun ungroup(profile: String, id: String) { entries.remove(group(profile, id).id) }
    fun removeTab(profile: String, tab: String) { all(profile).forEach { it.tabs.remove(tab) } }
    fun removeProfile(profile: String) { all(profile).map { it.id }.forEach(entries::remove) }
    fun restore(item: Group, valid: Set<String>) {
        require(item.id.matches(Regex("group[0-9]+")) && item.id !in entries)
        val number = requireNotNull(item.id.removePrefix("group").toLongOrNull())
        require(number in 1L..(Long.MAX_VALUE - 18000L))
        require(all(item.profile).size < 60)
        validate(item.name, item.color)
        val accepted = item.tabs.filter { it in valid && it != "popup" && membership(item.profile, it) == null }
        entries[item.id] = item.copy(name = item.name.trim(), tabs = LinkedHashSet(accepted))
        nextId = maxOf(nextId, number + 1)
    }
    private fun validate(name: String, color: Int) {
        require(name.trim().length in 1..40) { "Group name must contain 1–40 characters" }
        require(color in 0..5) { "Invalid group color" }
    }
}
