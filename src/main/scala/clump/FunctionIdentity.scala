package clump

case class FunctionIdentity private (cls: Class[_], externalParams: List[Any])

object FunctionIdentity {
  
  // Functions don't have a common interface
  type Function = Any
  
  def apply(function: Function) = {
    val cls = function.getClass
    new FunctionIdentity(cls, fieldValuesFor(function, cls))
  }
  
  private[this] def fieldValuesFor(function: Function, cls: Class[_]) =
    fieldsFor(cls).map(_.get(function))
  
  private[this] def fieldsFor(cls: Class[_]) = {
    val fields = cls.getDeclaredFields
    fields.foreach(_.setAccessible(true))
    fields.toList
  }
}
