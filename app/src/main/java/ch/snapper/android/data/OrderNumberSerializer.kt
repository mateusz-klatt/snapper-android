package ch.snapper.android.data

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Keeps decimal digits in a JSON number; the backend still validates a finite float. */
object OrderNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OrderNumber", PrimitiveKind.DOUBLE)
    /** The experimental raw literal API avoids JsonPrimitive's floating-point encoding path. */
    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        require(OrderNumbers.isSupported(value)) { "Order number must be positive and within the supported numeric range" }
        val json = encoder as? JsonEncoder ?: throw SerializationException("Order numbers require JSON")
        json.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
    }
    override fun deserialize(decoder: Decoder): BigDecimal = DecimalSerializer.deserialize(decoder)
}
