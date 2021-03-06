package com.axiomalaska.sos.source.observationretriever

import com.axiomalaska.sos.source.StationQuery
import java.util.Calendar
import com.axiomalaska.sos.source.data.LocalStation
import net.opengis.ows.x11.ExceptionReportDocument
import scala.collection.mutable
import net.opengis.gml.x32.TimeInstantType
import net.opengis.gml.x32.ValueArrayPropertyType
import com.axiomalaska.sos.source.data.ObservationValues
import com.axiomalaska.sos.source.data.LocalSensor
import com.axiomalaska.sos.source.data.LocalPhenomenon
import scala.collection.JavaConversions._
import org.apache.log4j.Logger
import com.axiomalaska.phenomena.Phenomenon
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import com.axiomalaska.phenomena.Phenomena
import scala.xml.Node
import org.joda.time.format.DateTimeFormat

abstract class SosObservationRetriever(private val stationQuery:StationQuery) 
	extends ObservationValuesCollectionRetriever {
  
  // ---------------------------------------------------------------------------
  // Private Data
  // ---------------------------------------------------------------------------
  
  private val LOGGER = Logger.getLogger(getClass())
  private val sosRawDataRetriever = new SosRawDataRetriever()
  private val dateParser = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ")
  
  protected val serviceUrl:String
  
  // ---------------------------------------------------------------------------
  // ObservationValuesCollectionRetriever Members
  // ---------------------------------------------------------------------------
  
  def getObservationValues(station: LocalStation, sensor: LocalSensor, 
      phenomenon: LocalPhenomenon, startDate: DateTime):List[ObservationValues] ={

    val thirdyDaysOld = DateTime.now().minusDays(30)
    
    val adjustedStartDate = if(startDate.isBefore(thirdyDaysOld)){
      thirdyDaysOld
    }
    else{
      startDate
    }
    
    val sensorForeignId = getSensorForeignId(phenomenon)
    
    val rawData = sosRawDataRetriever.getRawData(serviceUrl,
          station.databaseStation.foreign_tag,
          sensorForeignId, adjustedStartDate, DateTime.now())
          
//    println(rawData)

    val xmlResult = scala.xml.XML.loadString(rawData)

    xmlResult.find(node => node.label == "CompositeObservation") match {
      case Some(compositeObNode) => {
        buildSensorObservationValues(compositeObNode,
          station, sensor, phenomenon, startDate)
      }
      case None => {
        LOGGER.error("station ID: " + station.databaseStation.foreign_tag)
        Nil
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Private Members
  // ---------------------------------------------------------------------------

  /**
   * The SOS that we are pulling the data from has different Phenomenon URLs than the SOS
   * we are placing the data into. For example the pulling data SOS has one URL for
   * wind where the SOS we are pushing data into has three wind_speed, wind_direction, and wind_gust
   */
  private def getSensorForeignId(phenomenon: Phenomenon): String = {
    val localPhenomenon = phenomenon.asInstanceOf[LocalPhenomenon]

    if (localPhenomenon.getTag == Phenomena.instance.AIR_PRESSURE.getTag) {
      "http://mmisw.org/ont/cf/parameter/air_pressure"
    } else if (localPhenomenon.getTag == Phenomena.instance.AIR_TEMPERATURE.getTag) {
      "http://mmisw.org/ont/cf/parameter/air_temperature"
    } else if (localPhenomenon.getTag == Phenomena.instance.SEA_WATER_TEMPERATURE.getTag) {
      "http://mmisw.org/ont/cf/parameter/sea_water_temperature"
    } else if (localPhenomenon.getTag == Phenomena.instance.CURRENT_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.CURRENT_SPEED.getTag) {
      "http://mmisw.org/ont/cf/parameter/currents"
    } else if (localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_HEIGHT_ABOVE_SEA_LEVEL.getTag) {
      "http://mmisw.org/ont/cf/parameter/water_surface_height_above_reference_datum"
    } else if (localPhenomenon.getTag ==
      Phenomena.instance.SEA_SURFACE_HEIGHT_AMPLITUDE_DUE_TO_GEOCENTRIC_OCEAN_TIDE.getTag) {
      "http://mmisw.org/ont/cf/parameter/sea_surface_height_amplitude_due_to_equilibrium_ocean_tide"
    } else if (localPhenomenon.getTag == Phenomena.instance.WIND_FROM_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.WIND_SPEED_OF_GUST.getTag ||
      localPhenomenon.getTag == Phenomena.instance.WIND_SPEED.getTag ||
      localPhenomenon.getTag == Phenomena.instance.WIND_GUST_FROM_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.WIND_VERTICAL_VELOCITY.getTag) {
      "http://mmisw.org/ont/cf/parameter/winds"
    } else if (localPhenomenon.getTag == Phenomena.instance.SALINITY.getTag()) {
      "http://mmisw.org/ont/cf/parameter/sea_water_salinity"
    } else if (localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_SWELL_WAVE_TO_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_WIND_WAVE_TO_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_WIND_WAVE_PERIOD.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_WIND_WAVE_SIGNIFICANT_HEIGHT.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_SWELL_WAVE_PERIOD.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_SWELL_WAVE_SIGNIFICANT_HEIGHT.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_SWELL_WAVE_TO_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SEA_SURFACE_DOMINANT_WAVE_TO_DIRECTION.getTag ||
      localPhenomenon.getTag == Phenomena.instance.DOMINANT_WAVE_PERIOD.getTag ||
      localPhenomenon.getTag == Phenomena.instance.SIGNIFICANT_WAVE_HEIGHT.getTag ||
      localPhenomenon.getTag == Phenomena.instance.MEAN_WAVE_PERIOD.getTag) {
      "http://mmisw.org/ont/cf/parameter/waves"
    } else {
      throw new Exception("Sensor Foreign Id not found: " + phenomenon.getTag())
    }
  }
  
  private case class NamedQuantity(name:String, value:Double)
  
  private def buildSensorObservationValues(
    compositeObservationDocument: Node, 
    station: LocalStation, sensor: LocalSensor, phenomenon: LocalPhenomenon, 
    startDate: DateTime): List[ObservationValues] = {
      
    val observationValuesCollection = createSensorObservationValuesCollection(
        station, sensor, phenomenon)
    
    val resultNode = compositeObservationDocument \ "result"
    
    val arrayNodeOption = (resultNode \ "Composite" \ "valueComponents" \ 
    	"Array" \ "valueComponents" \ "Composite" \ "valueComponents" \ "Array").headOption

    for {
      arrayNode <- arrayNodeOption
      compositeNode <- arrayNode \\ "Composite"
      valueComponents <- (compositeNode \ "valueComponents").headOption
      compositeContextNode <- (valueComponents \ "CompositeContext").headOption
      val dateTime = createDate(compositeContextNode)
      if (dateTime.isAfter(startDate))
      namedQuantity <- getNamedQuantities(valueComponents)
    } {
      observationValuesCollection.find(observationValues =>
        observationValues.observedProperty.foreign_tag ==
          namedQuantity.name) match {
        case Some(sensorObservationValue) =>
          sensorObservationValue.addValue(namedQuantity.value, dateTime)
        case None => //println("namedQuantityType.getName(): " + namedQuantityType.getName())
      }
    }

    observationValuesCollection
  }
  
  private def createDate(compositeContextNode:Node):DateTime ={
    (compositeContextNode \\ "timePosition").headOption match{
      case Some(timePositionNode) =>{
        dateParser.parseDateTime(timePositionNode.text)
      }
      case None => throw new Exception("did not find datetime")
    }
  }
  
  private def getNamedQuantities(valueComponentsNode:Node):List[NamedQuantity] ={
    (for{valueNode <- (valueComponentsNode \ "CompositeValue")
      quantity <- valueNode \\ "Quantity"
      names <- quantity.attribute("name")
      nameNode <- names.headOption
      val name = nameNode.text
      if(quantity.text.nonEmpty)
      val value = quantity.text.toDouble} yield{
      NamedQuantity(name, value)
    }).toList
  }
  
  private def createSensorObservationValuesCollection(station: LocalStation, sensor: LocalSensor,
    phenomenon: LocalPhenomenon): List[ObservationValues] = {
    val observedProperties = stationQuery.getObservedProperties(
      station.databaseStation, sensor.databaseSensor, phenomenon.databasePhenomenon)

    for (observedProperty <- observedProperties) yield {
      new ObservationValues(observedProperty, sensor, phenomenon, observedProperty.foreign_units)
    }
  }
}