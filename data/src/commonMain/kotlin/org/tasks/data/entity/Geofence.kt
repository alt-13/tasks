package org.tasks.data.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonNames
import org.tasks.CommonParcelable
import org.tasks.CommonParcelize
import org.tasks.data.db.Table

@Serializable
@CommonParcelize
@Entity(
    tableName = Geofence.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["_id"],
            childColumns = ["task"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class Geofence @OptIn(ExperimentalSerializationApi::class) constructor(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "geofence_id")
    @Transient
    val id: Long = 0,
    @ColumnInfo(name = "task", index = true)
    @Transient
    val task: Long = 0,
    @ColumnInfo(name = "place")
    val place: String? = null,
    // @JsonNames: backups from before May 2020 use the old Java field names
    @ColumnInfo(name = "arrival")
    @JsonNames("arrival")
    val isArrival: Boolean = false,
    @ColumnInfo(name = "departure")
    @JsonNames("departure")
    var isDeparture: Boolean = false,
) : CommonParcelable {
    companion object {
        const val TABLE_NAME = "geofences"
        val TABLE = Table(TABLE_NAME)
        val TASK = TABLE.column("task")
        val PLACE = TABLE.column("place")
    }
}