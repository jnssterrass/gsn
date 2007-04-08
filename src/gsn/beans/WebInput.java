/**
  * @author Ali Salehi (AliS, ali.salehi-at-epfl.ch)<br>
 * Creation time : Dec 15, 2006@8:09:45 PM<br>
 */
package gsn.beans;

import java.io.Serializable;


public class WebInput implements Serializable{
   private String name;
   private DataField[] parameters;
   
   /**
    * @return the commandName
    */
   public String getName ( ) {
      return name;
   }
   
   /**
    * @return the inputParams
    */
   public DataField [ ] getParameters ( ) {
      return parameters;
   }
   
}
