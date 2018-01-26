package hello.data.account

import java.io.Serializable
import javax.persistence.*


@Entity
@Embeddable
@Table(name="account")
data class Account(
		val name: String = "",
		val password: String = "",
		var balance: Double = 0.0
) : Serializable {
	@javax.persistence.Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	val id: Long? = null
	
}