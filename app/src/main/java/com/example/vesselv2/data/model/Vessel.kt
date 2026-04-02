package com.example.vesselv2.data.model

data class Vessel(
    val id: String = "",
    val vesselName: String = "",
    val date: String = "",
    val corn: String = "",
    val row: String = "",
    val turnburckle: String = "",
    val floor: String = "",
    val flan: String = "",
    val twin: String = "",
    val Notes: String = ""
) {
    companion object {
        const val COLLECTION = "Lashing"
        const val FIELD_VESSEL_NAME = "vesselName"
    }

    fun toMap() = hashMapOf(
        "vesselName" to vesselName,
        "date" to date,
        "corn" to corn,
        "row" to row,
        "turnburckle" to turnburckle,
        "floor" to floor,
        "flan" to flan,
        "twin" to twin,
        "Notes" to Notes
    )
}
