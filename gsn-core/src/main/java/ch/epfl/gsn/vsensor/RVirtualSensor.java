/**
* Global Sensor Networks (GSN) Source Code
* Copyright (c) 2006-2016, Ecole Polytechnique Federale de Lausanne (EPFL)
* 
* This file is part of GSN.
* 
* GSN is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* GSN is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License
* along with GSN.  If not, see <http://www.gnu.org/licenses/>.
* 
* File: src/ch/epfl/gsn/vsensor/RVirtualSensor.java
*
* @author sp3dy
* @author Ali Salehi
* @author Mehdi Riahi
*
*/

package ch.epfl.gsn.vsensor;

import java.io.File;
import java.io.Serializable;
import java.util.Hashtable;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.slf4j.LoggerFactory;

import ch.epfl.gsn.beans.DataField;
import ch.epfl.gsn.beans.DataTypes;
import ch.epfl.gsn.beans.StreamElement;
import ch.epfl.gsn.vsensor.AbstractVirtualSensor;
import ch.epfl.gsn.vsensor.RVirtualSensor;

import org.slf4j.Logger;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.Rserve.RConnection;

public class RVirtualSensor extends AbstractVirtualSensor
{
  
  private static final String                                  WINDOW_SIZE = "window_size";
  private static final String                                  STEP_SIZE   = "step_size";
  private static final String                                  OUTPUT_PLOT = "gsn_plot";
  private static final String                                  SCRIPT      = "script";
  private static final String                                  SERVER      = "server";
  private static final String                                  PORT        = "port";
  public String                                                script      = null;
  public String                                                stype       = null;
  
  private static transient Logger                              logger      = LoggerFactory.getLogger(RVirtualSensor.class);
  
  private Hashtable<String, ArrayBlockingQueue<StreamElement>> circularBuffers;
  
  private int                                                  windowSize  = -1;
  private int                                                  stepSize    = -1;
  
  public RConnection                                           rc;
  public REXP                                                  xp;
  
  public TreeMap<String, String>                               params      = null;
  
  public boolean initialize()
  {
    params = getVirtualSensorConfiguration().getMainClassInitialParams();
    
    circularBuffers = new Hashtable<String, ArrayBlockingQueue<StreamElement>>();
    // Get the parameters from the XML configuration file
    
    String param = null;
    param = params.get(WINDOW_SIZE);
    
    if (param != null)
    {
      windowSize = Integer.parseInt(param);
    } else
    {
      logger.error("The required parameter: >" + WINDOW_SIZE + "<+ is missing.from the virtual sensor configuration file.");
      return false;
    }
    
    param = params.get("script_type");
    if (param != null)
    {
      stype = param;
    } else
    {
      stype = "computation";
    }
    
    param = params.get(STEP_SIZE);
    
    if (param != null)
    {
      stepSize = Integer.parseInt(param);
    } else
    {
      logger.error("The required parameter: >" + STEP_SIZE + "<+ is missing.from the virtual sensor configuration file.");
      return false;
    }
    
    if (windowSize < stepSize)
    {
      logger.error("The parameter " + WINDOW_SIZE + " must be greater or equal to the parameter " + STEP_SIZE);
      return false;
    }
    
    return true;
  }
  
  public void dataAvailable(String inputStreamName, StreamElement streamElement)
  {
    ArrayBlockingQueue<StreamElement> circularBuffer = circularBuffers.get(inputStreamName);
    
    // Get the circular buffer that matches the input stream. Create a new one
    // if none exists
    if (circularBuffer == null)
    {
      circularBuffer = new ArrayBlockingQueue<StreamElement>(windowSize);
      circularBuffers.put(inputStreamName, circularBuffer);
    }
    try
    {
      circularBuffer.put(streamElement);
      
      logger.debug("Window for " + inputStreamName + " contains: " + circularBuffer.size() + " of " + windowSize);
      
      if (circularBuffer.size() == windowSize)
      {
        logger.debug("Window for " + inputStreamName + " contains: " + circularBuffer.size() + " of " + windowSize);
        
        // Connect to Rserve and assign global variables
        try
        {
          rc = new RConnection(params.get(SERVER), Integer.parseInt(params.get(PORT)));
          
          logger.debug("Connected to R server " + params.get(SERVER) + ":" + params.get(PORT));
          
          String[] fieldname = streamElement.getFieldNames();
          
          logger.debug("Sending " + fieldname.length + " data attributes to R server.");
          
          // Assign R vector variables prior the script
          for (int n = 0; n < fieldname.length; n++)
          {
            // Build the window
            double [] values = new double[windowSize];
            StreamElement elt = null;
            
            // convert the circular buffer to an array
            Object[] elts = circularBuffer.toArray();
            for (int i = 0; i < elts.length; i++)
            {
              elt = (StreamElement) elts[i];
              values[i] = ((Number) elt.getData()[n]).doubleValue(); //
            }
            
            // assign vectors as R variables
            rc.assign("gsn_" + fieldname[n].toLowerCase(), values);
          }
          
          logger.debug("Done.");
          
          // read the R script
          // open the script file every time we do some processing (this can be
          // improved).
          File file = new File(params.get(SCRIPT).toString());
          script = FileUtils.readFileToString(file, "UTF-8");
          
          logger.debug("Sending R script.");
          
          // evaluate the R script
          rc.voidEval(script);
          logger.debug("Done.");
          
          // get the output timestamp
          logger.debug("Performing computation in R server (please wait).");
          
          // collect vector values after computation
          DataField[] outStructure = null;
          
          outStructure = getVirtualSensorConfiguration().getOutputStructure();
          
          String[] plotFieldName = new String[outStructure.length];
          Byte[] plotFieldType = new Byte[outStructure.length];
          
          for (int w = 0; w < outStructure.length; w++)
          {
            plotFieldName[w] = outStructure[w].getName();
            plotFieldType[w] = outStructure[w].getDataTypeID();
          }
          
          Serializable[] outputData = null;
          StreamElement se = null;
          
          Byte[] fieldType = streamElement.getFieldTypes();
          
          // check if we have defined more attributes in the output structure
          if (outStructure.length > fieldname.length)
          {
            outputData = new Serializable[outStructure.length];
          } else
          {
            outputData = new Serializable[fieldname.length];
          }
          
          for (int n = 0; n < fieldname.length; n++)
          {
            // evaluate/get attribute data from R server
            xp = rc.parseAndEval(fieldname[n].toLowerCase());
            
            if (fieldType[n] == DataTypes.DOUBLE)
            {
              double[] b1 = xp.asDoubles();
              outputData[n] = b1[b1.length - 1];
            }
            
            if (fieldType[n] == DataTypes.INTEGER)
            {
              int[] b1 = xp.asIntegers();
              outputData[n] = b1[b1.length - 1];
            }
            
            if (fieldType[n] == DataTypes.BIGINT)
            {
              int[] b1 = xp.asIntegers();
              outputData[n] = (long) b1[b1.length - 1];
            }
          }
          
          int len1 = outStructure.length;
          int len2 = fieldname.length;
          
          // check if we have defined more attributes in the output structure
          if (len1 > len2)
          {
            if (stype.equals("plot"))
            {
              xp = rc.parseAndEval("gsn_plot");
              outputData[len2] = xp.asBytes();
              
              se = new StreamElement(plotFieldName, plotFieldType, outputData);
            }
          } else
          {
            se = new StreamElement(fieldname, fieldType, outputData);
          }
          
          logger.debug("Computation finished.");
          
          dataProduced(se);
          logger.debug("Stream published: " + se.toString().toLowerCase());
          
          // Close connection to R server
          rc.close();
          logger.debug("Connection to R server closed.");
          
        } catch (Exception e)
        {
          logger.warn(e.getMessage());
          // Close connection to R server
          logger.debug("Connection to R server closed.");
          rc.close();
        }
        
        // Remove step size elements from the beginning of the buffer
        for (int i = 0; i < stepSize; i++)
        {
          try
          {
            circularBuffer.take();
          } catch (InterruptedException e)
          {
            logger.warn(e.getMessage(), e);
          }
        }
        
      }
      
      // end if if for window
    } catch (InterruptedException e)
    {
      logger.warn(e.getMessage(), e);
    }
    
  }
  
  public void dispose()
  {
    
  }
  
  public int getPredicateValueAsIntWithException(String parameter)
  {
    String param = getVirtualSensorConfiguration().getMainClassInitialParams().get(parameter);
    if (param == null)
      throw new java.lang.RuntimeException("The required parameter: >" + parameter + "<+ is missing.from the virtual sensor configuration file.");
    else
      return Integer.parseInt(param);
  }
  
}
