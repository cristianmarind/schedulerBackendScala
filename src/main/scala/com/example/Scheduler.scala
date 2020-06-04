/*//#full-example
package com.example


import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.example.GreeterMain.SayHello

//#greeter-actor
object Greeter {
  final case class Greet(whom: String, msg: String, replyTo: ActorRef[Greeted])
  final case class Greeted(whom: String, msg: String, from: ActorRef[Greet])

  def apply(): Behavior[Greet] = Behaviors.receive { (context, message) =>
    context.log.info("Hello {}!", message.msg)
    //#greeter-send-messages
    message.replyTo ! Greeted(message.whom, message.msg, context.self)
    //#greeter-send-messages
    Behaviors.same
  }
}
//#greeter-actor

//#greeter-bot
object GreeterBot {

  def apply(max: Int): Behavior[Greeter.Greeted] = {
    bot(0, max)
  }

  private def bot(greetingCounter: Int, max: Int): Behavior[Greeter.Greeted] =
    Behaviors.receive { (context, message) =>
      val n = greetingCounter + 1
      context.log.info("Greeting {} for {}", n, message.whom)
      if (n == max) {
        Behaviors.stopped
      } else {
        message.from ! Greeter.Greet(message.whom, message.msg, context.self)
        bot(n, max)
      }
    }
}
//#greeter-bot

//#greeter-main
object GreeterMain {

  final case class SayHello(i: String, name: String)

  def apply(): Behavior[SayHello] =
    Behaviors.setup { context =>
      //#create-actors
      val greeter = context.spawn(Greeter(), "greeter")
      //#create-actors

      Behaviors.receiveMessage { message =>
        //#create-actors
        val replyTo = context.spawn(GreeterBot(max = 3), message.i)
        //#create-actors
        greeter ! Greeter.Greet(message.i, message.name, replyTo)
        Behaviors.same
      }
    }
}
//#greeter-main


object Scheduler extends App {
  val greeterMain: ActorSystem[GreeterMain.SayHello] = ActorSystem(GreeterMain(), "AkkaQuickStart")
  greeterMain ! SayHello("Pedro", "Charles")

}*/

//#full-example


package com.example
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.DispatcherSelector
import akka.dispatch.Filter.filterOf

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.collection.immutable.List
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import java.util.concurrent.Executors

final case class PROCESO(idProceso: String, segundos: Int)
final case class ICPU(esUtilizado: Boolean, interfazCPU: ActorRef[InterfazCPUEvent])

final case class Procesar(proceso: PROCESO, interfazCPU: ActorRef[InterfazCPUEvent], scheduler: ActorRef[SchedulerEvent])

sealed trait InterfazCPUEvent
final case class InterfazCPUProcesar(proceso: PROCESO, scheduler: ActorRef[SchedulerEvent]) extends InterfazCPUEvent
final case class ProcesadorLiberado(proceso: PROCESO, scheduler: ActorRef[SchedulerEvent]) extends InterfazCPUEvent

sealed trait MLFQEvent
final case class Encolar(proceso: PROCESO) extends MLFQEvent
final case class Desencolar(idProceso: String) extends MLFQEvent
final case class GetProximoProceso(interfazCPU: ActorRef[InterfazCPUEvent], scheduler: ActorRef[SchedulerEvent]) extends MLFQEvent

sealed trait SchedulerEvent
final case class SchedulerProcesar(idProceso: String, segundos: Int) extends SchedulerEvent
final case class NuevoEstadoDeProceso(proceso: PROCESO, estado: Int, interfazCPU: ActorRef[InterfazCPUEvent]) extends SchedulerEvent
final case class NuevosProcesoAProcesar(proceso: PROCESO, interfazCPU: ActorRef[InterfazCPUEvent], mlfqVacia: Boolean) extends SchedulerEvent
final case class LiberarInterfazCPU(interfazCPU: ActorRef[InterfazCPUEvent]) extends SchedulerEvent

sealed trait IOEvent
//final case class IOModificacionMLFQ(msg: String) extends IOEvent
final case class IOEvents() extends IOEvent

object CPU {
  def apply(): Behavior[Procesar] =
    Behaviors.setup { context =>
      /*implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))*/
      //println("CPU Creada")
      Behaviors.receiveMessage {
        case Procesar(proceso: PROCESO, interfazCPU: ActorRef[InterfazCPUEvent], scheduler: ActorRef[SchedulerEvent]) =>
          //triggerFutureBlockingOperation(segundos, interfazCPU)(executionContext)
          (Future {
            Thread.sleep(proceso.segundos * 1000)
            interfazCPU ! ProcesadorLiberado(proceso, scheduler)
          })/*(executionContext)*/
          Behaviors.same
      }
    }
  /*def triggerFutureBlockingOperation(segundos: Int, interfazCPU: ActorRef[InterfazCPUEvent])(implicit ec: ExecutionContext): Future[Unit] = {
    (Future {
      Thread.sleep(segundos * 1000)
      interfazCPU ! ProcesadorLiberado()
    })(ec)
  }*/
}

object InterfazCPU {
  def apply(cpu: ActorRef[Procesar]): Behavior[InterfazCPUEvent] = {
    ejecutar(cpu, false)
  }
  private def ejecutar(cpu: ActorRef[Procesar], esUtilizado: Boolean): Behavior[InterfazCPUEvent] =
    Behaviors.setup { context =>
      /*implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))*/
      //println("InterfazCPU nuevo comportamiento")
      Behaviors.receiveMessage {
        case InterfazCPUProcesar(proceso, scheduler) =>
          if(esUtilizado){
            scheduler ! NuevoEstadoDeProceso(proceso, 0, context.self)
            Behaviors.same
          }else{
            scheduler ! NuevoEstadoDeProceso(proceso, 1, context.self)
            cpu ! Procesar(proceso, context.self, scheduler)
            ejecutar(cpu, true)
          }
        case ProcesadorLiberado(proceso: PROCESO, scheduler: ActorRef[SchedulerEvent]) =>
          //println(s"ProcesadorLiberado termino ${proceso.idProceso}")
          scheduler ! NuevoEstadoDeProceso(proceso, 2, context.self)
          ejecutar(cpu, false)
      }
    }
}

object MLFQ {
  def apply(): Behavior[MLFQEvent] = {
    ejecutar(List[PROCESO]())
  }
  private def ejecutar(procesos: List[PROCESO]): Behavior[MLFQEvent] =
    Behaviors.setup { context =>
      /*implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))*/
      //println("MLFQ nuevo comportamiento")
      Behaviors.receiveMessage {
        case Encolar(proceso) =>
          /*CODIGO MALO POR LA VARIABLE TEMPORTAL
          * val temp = procesos.appended(proceso)
          scheduler ! NuevosProcesos(temp)
          ejecutar(temp)
          * */
          //println(s"Encolar ${proceso.idProceso} ${Thread.currentThread().getName()}")
          ejecutar(procesos.appended(proceso))
        case Desencolar(idProceso) =>
          val temp = procesos.filterNot(p => p.idProceso == idProceso)
          //println("Desencolar")
          ejecutar(temp)
        case GetProximoProceso(interfazCPU: ActorRef[InterfazCPUEvent], scheduler: ActorRef[SchedulerEvent]) =>
          if(procesos.length == 0){
            //Habilitar la  interfazCPU
            scheduler ! LiberarInterfazCPU(interfazCPU)
            Behaviors.same
          }else{
            val np = procesos.apply(0)
            val temp = procesos.filterNot(p => p.idProceso == np.idProceso)
            //println(s"GetProximoProceso ${np.segundos}")
            scheduler ! NuevosProcesoAProcesar(np, interfazCPU, temp.length == 0)
            ejecutar(temp)
          }
      }
    }
}



object Scheduler {
  def apply(cpus: List[ICPU], mlfq: ActorRef[MLFQEvent]): Behavior[SchedulerEvent] = {
    ejecutar(cpus, mlfq, false)
  }
  private def ejecutar(cpus: List[ICPU], mlfq: ActorRef[MLFQEvent], procesosPendientes: Boolean): Behavior[SchedulerEvent] =
    Behaviors.setup { context =>
      /*implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))*/
      //println("Scheduler nuevo comportamiento")
      Behaviors.receiveMessage {
        case SchedulerProcesar(idProceso: String, segundos: Int) =>
          //println(s"Proceso ${idProceso} mandado a encolar   ${Thread.currentThread().getName()}")
          /*
          mlfq ! Encolar(PROCESO(idProceso, segundos))
          Thread.sleep(2000)
          println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()}")
          mlfq ! GetProximoNivel(context.self)//Ejecutar nuevo proceso
          */
          /*(for{ //for comprehension
            /*x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))//todo lo que haga lo encapsule dentro de success
            y <- pp()
            z <- xx(x, y)
            _ = j()*/
           */
          /*for{
              x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
              _ = {
                println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
                mlfq ! GetProximoNivel(context.self)
              }
            } yield x/*(ecParaRecuperacion)*/*/
          /*(for{
            //x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
            x <- mlfq ! Encolar(PROCESO(idProceso, segundos))
            _ = {
              println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
              mlfq ! GetProximoNivel(context.self)
            }
          } yield x)(executionContext)*/
          /*for{
            //x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
            x <- {
              val f = Future {
                mlfq ! Encolar(PROCESO(idProceso, segundos))
              }

              f.filter(filterOf(filterOf(new Function<String, Boolean>() {
                return true
              })))
            }
            _ = {
              println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
              mlfq ! GetProximoNivel(context.self)
            }
          } yield x*/
          val cpuLibre = cpus.find(i => !i.esUtilizado)
          mlfq ! Encolar(PROCESO(idProceso, segundos))
          if(cpuLibre != None){
            mlfq ! GetProximoProceso(cpuLibre.head.interfazCPU, context.self)
          }
          ejecutar(cpus.map((c: ICPU) => {
            if(cpuLibre != None && c == cpuLibre.head){
              ICPU(true, c.interfazCPU)
            }else {
              c
            }
          }), mlfq, true)
        case NuevoEstadoDeProceso(proceso, estado, interfazCPU) =>
          if(estado == 1){
            println(s"Proceso ${proceso.idProceso} ejecutandose en ${interfazCPU.path.name}")
            /*mlfq ! Desencolar(proceso.idProceso)
            Thread.sleep(2000)
            for{
              x <- Success(mlfq ! Desencolar(proceso.idProceso))
              _ = println(s"Proceso terminado señal ${x}")
                } yield x*/
          }else if(estado == 2){
            println(s"Proceso ${proceso.idProceso} terminado en ${interfazCPU.path.name}")
            //La CPU debe de ser la que termino su funcionamiento
            mlfq ! GetProximoProceso(interfazCPU, context.self)
          }else{
            println(s"Proceso ${proceso.idProceso} no pudo ser ejecuctado")
          }
          Behaviors.same
        case NuevosProcesoAProcesar(proceso: PROCESO, interfazCPU: ActorRef[InterfazCPUEvent], mlfqVacia: Boolean) =>
          //println(s"xxxxxxx ${proceso.segundos}")
          if(proceso.segundos > 0){
            //println(s"Proceso enviado a Procesarse")
            interfazCPU ! InterfazCPUProcesar(proceso, context.self)
          }
          //for (p <- procesos.iterator) router ! InterfazCPUProcesar(p, context.self)
          ejecutar(cpus, mlfq, true)
        case LiberarInterfazCPU(interfazCPU: ActorRef[InterfazCPUEvent]) =>
          //println("LiberarInterfazCPU1")
          ejecutar(cpus.map((c: ICPU) => {
            if(c.interfazCPU == interfazCPU){
              //println("LiberarInterfazCPU2")
              ICPU(false, c.interfazCPU)
            }else {
              c
            }
          }), mlfq, true)
      }
    }
  /*def apply(): Behavior[SchedulerEvent] =
    Behaviors.setup { context =>
      /*val pool = Routers.pool(poolSize = 1)(
        // make sure the workers are restarted if they fail
        Behaviors.supervise(InterfazCPU()).onFailure[Exception](SupervisorStrategy.restart))
      val router = context.spawn(pool, "cpus")*/
      //implicit val ecParaRecuperacion = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1))
      implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))
      val interfazCPU = context.spawn(InterfazCPU(), "interfazCPU")
      val mlfq = context.spawn(MLFQ(), "mlfq")
      Behaviors.receiveMessage {
        case SchedulerProcesar(idProceso: String, segundos: Int) =>
          println(s"Proceso ${idProceso} mandado a encolar   ${Thread.currentThread().getName()}")
          /*
          mlfq ! Encolar(PROCESO(idProceso, segundos))
          Thread.sleep(2000)
          println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()}")
          mlfq ! GetProximoNivel(context.self)//Ejecutar nuevo proceso
          */
          /*(for{ //for comprehension
            /*x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))//todo lo que haga lo encapsule dentro de success
            y <- pp()
            z <- xx(x, y)
            _ = j()*/
           */
          /*for{
              x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
              _ = {
                println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
                mlfq ! GetProximoNivel(context.self)
              }
            } yield x/*(ecParaRecuperacion)*/*/
          /*(for{
            //x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
            x <- mlfq ! Encolar(PROCESO(idProceso, segundos))
            _ = {
              println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
              mlfq ! GetProximoNivel(context.self)
            }
          } yield x)(executionContext)*/
          /*for{
            //x <- Future.successful(mlfq ! Encolar(PROCESO(idProceso, segundos)))
            x <- {
              val f = Future {
                mlfq ! Encolar(PROCESO(idProceso, segundos))
              }

              f.filter(filterOf(filterOf(new Function<String, Boolean>() {
                return true
              })))
            }
            _ = {
              println(s"Encolado ${idProceso}  ${Thread.currentThread().getName()} ${x}")
              mlfq ! GetProximoNivel(context.self)
            }
          } yield x*/
          mlfq ! Encolar(PROCESO(idProceso, segundos))
          mlfq ! GetProximoNivel(context.self)
          Behaviors.same
        case NuevoEstadoDeProceso(proceso, estado) =>
          if(estado == 1){
            println(s"Proceso ${proceso.idProceso} en ejecucion")
            /*mlfq ! Desencolar(proceso.idProceso)
            Thread.sleep(2000)
            for{
              x <- Success(mlfq ! Desencolar(proceso.idProceso))
              _ = println(s"Proceso terminado señal ${x}")
                } yield x*/
          }else if(estado == 2){
            println(s"Proceso ${proceso.idProceso} terminado")
          }else{
            println(s"Proceso ${proceso.idProceso} no pudo ser ejecuctado")
          }
          Behaviors.same
        case NuevosProcesos(procesos) =>
          println(s"Nuevos Procesos ${procesos.length}")
          for (p <- procesos.iterator){
            println(s"Proceso enviado a Procesarse")
            interfazCPU ! InterfazCPUProcesar(p, context.self)
          }
          //for (p <- procesos.iterator) router ! InterfazCPUProcesar(p, context.self)
          Behaviors.same
      }
    }*/
}

object IO {
  def apply(): Behavior[IOEvent] =
    Behaviors.setup { context =>
      /*implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))*/
      val mlfq = context.spawn(MLFQ(), "mlfq")
      val interfazCPU1 = context.spawn(InterfazCPU(context.spawn(CPU(), "cpu1")), "interfazCPU1")
      val icpu1 = ICPU(false, interfazCPU1)
      val interfazCPU2 = context.spawn(InterfazCPU(context.spawn(CPU(), "cpu2")), "interfazCPU2")
      val icpu2 = ICPU(false, interfazCPU2)
      val interfazCPU3 = context.spawn(InterfazCPU(context.spawn(CPU(), "cpu3")), "interfazCPU3")
      val icpu3 = ICPU(false, interfazCPU3)
      val cpus = List[ICPU](icpu1, icpu2, icpu3)
      val scheduler = context.spawn(Scheduler(cpus, mlfq), "scheduler")
      val request = Range(1, 10).toList
      request.map(r => {
        scheduler ! SchedulerProcesar(s"p-i-${r}", r)
      })
      request.map(r => {
        Thread.sleep(700)
        scheduler ! SchedulerProcesar(s"p-t-${r}", r)
      })
      Behaviors.receiveMessage {
        case IOEvents() =>
          Behaviors.same
      }
    }
}

object XXXXXX extends App {
  val greeterMain: ActorSystem[IOEvent] = ActorSystem(IO(), "AkkaQuickStart")
  //greeterMain ! SayHello("Pedro", "Charles")
  //val system: ActorSystem = ActorSystem(GreeterMain(), "helloAkka")
  //val scheduler: ActorRef = system.actorOf(Scheduler.props, "scheduler")
  //val cpu: ActorRef = system.actorOf(CPU(), "cpu")
  /*
  val request = Range(1,5).toList
  val r = request.map(r => {
    cpu ! Procesar(s"Proceso-${r}", r, scheduler)
  })*/
}












/*
object Actor {
  def apply(): Behavior[SchedulerEvent] =
    Behaviors.setup { context =>
      val Actor = context.spawn(Actor(), "nombre de Actor")
      Behaviors.receiveMessage {
        case Evento(Parametros) =>
          Behaviors.same
      }
    }
}
*/


/*
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
    Thread.sleep(700)
    operar(r, 5)
  }

})

r.map(ll =>{
  Await.result(ll, Duration.Inf)
})*/