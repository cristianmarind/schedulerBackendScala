import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration


case class CPU(var id: String, var estado: String)

val CPU1 = CPU("1", "L")
val CPU2 = CPU("2", "L")

def sumar(a:Int, b: Int, cpu: CPU) = {
  Thread.sleep(1000)
  println(s"Proceso la CPU ${cpu.id}")
  a + b
}

def operar(a: Int, b: Int): Int = {

  if (CPU1.estado == "L") {
    CPU1.estado = "O"
    val c = sumar(a, b, CPU1)
    CPU1.estado = "L"
    c
  } else if (CPU2.estado == "L") {
    CPU2.estado = "O"
    val c = sumar(a, b, CPU2)
    CPU2.estado = "L"
    c
  } else {
    operar(a ,b)
  }

}

val request = Range(0,10).toList
val r = request.map(r => {
  Future{
    operar(r, 5)
  }

})

r.map(ll =>{
  Await.result(ll, Duration.Inf)
})