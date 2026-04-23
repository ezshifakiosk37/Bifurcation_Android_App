interface VendingCommand {
    val action: String
    fun isValid(): Boolean
    fun toJsonString(): String
}