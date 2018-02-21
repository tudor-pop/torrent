package hello.business.command

import hello.data.order.Order
import hello.data.order.OrdersRepository

class DeleteOrder(
		val ordersRepository: OrdersRepository,
		val order: Order
) : Command {
	override var reverseCommand: Command? = null
		get() = CreateOrder(ordersRepository, order)
	
	override fun execute() {
		ordersRepository.save(order)
	}
}