package com.gpo.pilipala.core.util

/** av ⇄ BV conversion (new 2024 algorithm). */
object IdUtils {
    private const val XOR_CODE = 23442827791579L
    private const val MASK_CODE = 2251799813685247L
    private const val MAX_AID = 1L shl 51
    private const val BASE = 58L
    private const val TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    fun av2bv(aid: Long): String {
        val bytes = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
        var bvIndex = bytes.size - 1
        var tmp = (MAX_AID or aid) xor XOR_CODE
        while (tmp > 0) {
            bytes[bvIndex] = TABLE[(tmp % BASE).toInt()]
            tmp /= BASE
            bvIndex--
        }
        bytes.swap(3, 9)
        bytes.swap(4, 7)
        return String(bytes)
    }

    fun bv2av(bvid: String): Long {
        val chars = bvid.toCharArray()
        chars.swap(3, 9)
        chars.swap(4, 7)
        var tmp = 0L
        for (c in chars.copyOfRange(3, chars.size)) tmp = tmp * BASE + TABLE.indexOf(c)
        return (tmp and MASK_CODE) xor XOR_CODE
    }

    private fun CharArray.swap(a: Int, b: Int) {
        val t = this[a]; this[a] = this[b]; this[b] = t
    }

    private val bvRegex = Regex("BV[0-9A-Za-z]{10}")
    private val avRegex = Regex("(?i)av(\\d+)")

    fun findBvid(text: String): String? = bvRegex.find(text)?.value
    fun findAid(text: String): Long? = avRegex.find(text)?.groupValues?.get(1)?.toLongOrNull()
}
