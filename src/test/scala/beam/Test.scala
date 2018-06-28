package beam

object Test extends App {
  val l1 = List(1,2,3,4,5,6,7,8,9)
  val l2 = List()
  val l3 = List(Some(1),Some(2),None,Some(3))

  println(l1.reduce(sum))
  println(l2.reduceOption(sum).getOrElse(0))
  println(l3.flatten.reduce(sum))

  def sum(n1: Int, n2: Int) = n1 + n2
}
