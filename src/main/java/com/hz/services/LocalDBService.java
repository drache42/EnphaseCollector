package com.hz.services;

import com.hz.configuration.EnphaseCollectorProperties;
import com.hz.interfaces.*;
import com.hz.metrics.Metric;
import com.hz.models.database.*;
import com.hz.models.dto.PanelProduction;
import com.hz.models.events.MetricCollectionEvent;
import com.hz.models.events.SystemInfoEvent;
import com.hz.utils.Calculators;
import com.hz.utils.Convertors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class LocalDBService {

	private final EnphaseCollectorProperties properties;
	private final EnvoySystemRepository envoySystemRepository;
	private final EventRepository eventRepository;
	private final PanelRepository panelRepository;
	private final SummaryRepository summaryRepository;
	private final ElectricityRateRepository electricityRateRepository;

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void applicationReady() {
		this.upgradeRates();
		this.upgradeConversion();
		this.createSummaries();
	}

	private void upgradeConversion() {
		summaryRepository.updateAllSummariesWithConversion(properties.getRefreshAsMinutes());
	}

	private void upgradeRates() {
		ElectricityRate rate = this.getRateForDate(LocalDate.now());
		if (rate == null) {
			// First creation set effective to first summary event
			log.info("Upgrading Rate Table (First Run)");
			this.saveElectricityRate(new ElectricityRate(properties.getPaymentPerKiloWatt(), properties.getChargePerKiloWatt(), properties.getDailySupplyCharge()));
		} else if (properties.getEffectiveRateDate() == null && rate.getChargePerKiloWatt().compareTo(properties.getChargePerKiloWatt()) != 0) {
			// Rate has changes set new rate from today
			log.info("Upgrading Rate Table (New Rate Set as at Today)");
			this.saveElectricityRate(LocalDate.now(), new ElectricityRate(properties.getPaymentPerKiloWatt(), properties.getChargePerKiloWatt(), properties.getDailySupplyCharge()));
		} else if (properties.getEffectiveRateDate() != null && properties.getEffectiveRateDate().isAfter(rate.getEffectiveDate())) {
			// Rate is changing from new date
			log.info("Upgrading Rate Table (New Rate set effective {})", properties.getEffectiveRateDate());
			this.saveElectricityRate(properties.getEffectiveRateDate(), new ElectricityRate(properties.getPaymentPerKiloWatt(), properties.getChargePerKiloWatt(), properties.getDailySupplyCharge()));
		}
	}

	// Summarise the Event table at 5 minutes past midnight
	@Scheduled(cron="0 5 0 * * ?")
	@Transactional
	public void createSummaries() {
		LocalDateTime midnight = getMidnight();
		log.info("Summarising Event table prior to {}", midnight);
		try {
			List<DailySummary> dailies = eventRepository.findAllBefore(midnight);
			List<Total> gridImports = eventRepository.findAllExcessConsumptionBefore(midnight);
			List<Total> gridExports = eventRepository.findAllExcessProductionBefore(midnight);
			List<Total> maxProductions = eventRepository.findAllMaxProductionBefore(midnight);

			dailies.forEach(daily -> saveSummary(daily,
					findMatching(gridImports, daily.getDate()),
					findMatching(gridExports, daily.getDate()),
					findMatching(maxProductions, daily.getDate()), properties.getRefreshAsMinutes()));

			panelRepository.deleteEventsPanelByTimeBefore(midnight);
			panelRepository.deletePanelsByTimeBefore(midnight);
			eventRepository.deleteEventsByTimeBefore(midnight);
		} catch (Exception e) {
			log.error("Failed to summarise Event table: {} {}", e.getMessage(), e);
		}
	}

	@EventListener
	public void systemInfoListener(SystemInfoEvent systemEvent) {
		envoySystemRepository.save(systemEvent.getEnvoySystem());
	}

	@EventListener
	public void metricListener(MetricCollectionEvent metricCollectionEvent) {
		log.debug("Writing metric stats at {} with {} items to internal database", metricCollectionEvent.getCollectionTime(), metricCollectionEvent.getMetrics().size());

		Event event = new Event();
		event.setTime(metricCollectionEvent.getCollectionTime());

		event.setProduction(getMetric(metricCollectionEvent.getMetrics(), Metric.METRIC_PRODUCTION_CURRENT).map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO));
		event.setConsumption(getMetric(metricCollectionEvent.getMetrics(), Metric.METRIC_CONSUMPTION_CURRENT).map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO));
		event.setVoltage(getMetric(metricCollectionEvent.getMetrics(), Metric.METRIC_PRODUCTION_VOLTAGE).map(metric -> BigDecimal.valueOf(metric.getValue())).orElse(BigDecimal.ZERO));

		metricCollectionEvent.getMetrics().stream().filter(Metric::isSolarPanel).forEach(event::addSolarPanel);

		eventRepository.save(event);
	}

	private void saveSummary(DailySummary daily, Total gridImport, Total gridExport, Total highestOutput, BigDecimal conversionRate) {
		log.info("Saving Summary for {} with import {} and export {}", daily.getDate(), gridImport.getSummary(), gridExport.getSummary());
		summaryRepository.save(new Summary(daily, gridImport, gridExport, highestOutput, conversionRate));
	}

	public void saveElectricityRate(LocalDate effectiveDate, ElectricityRate electricityRate) {
		log.info("Storing new Rate effective {}", effectiveDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
		electricityRate.setEffectiveDate(effectiveDate);
		electricityRateRepository.save(electricityRate);
	}

	public void saveElectricityRate(ElectricityRate electricityRate) {
		Summary summary = summaryRepository.findFirst();
		if (summary == null) {
			// No Summaries yet
			this.saveElectricityRate(LocalDate.now(), electricityRate);
		} else {
			this.saveElectricityRate(summary.getDate(), electricityRate);
		}
	}

	private Total findMatching(List<Total> values, LocalDate matchDate) {
		return values.stream().
				filter(value -> matchDate.isEqual(value.getDate())).
				findFirst().
				orElseGet(EmptyTotal::new);
	}

	public ElectricityRate getRateForDate(LocalDate date) {
		return electricityRateRepository.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(date).orElse(new ElectricityRate(date));
	}

	private Optional<Metric> getMetric(List<Metric> metrics, String name) {
		return metrics.stream().filter(metric -> metric.getName().equalsIgnoreCase(name)).findFirst();
	}

	public EnvoySystem getSystemInfo() {
		return envoySystemRepository.findById(1L).orElseGet(EnvoySystem::new);
	}

	@Transactional(readOnly = true)
	public Event getLastEvent() {
		Optional<EnvoySystem> envoySystem = envoySystemRepository.findById(1L);

		return envoySystem.map(es -> eventRepository.findTopByTime(es.getLastReadTime())).orElseGet(Event::new);
	}

	public List<Event> getEventsForToday() {
		return eventRepository.findEventsByTimeAfter(getMidnight());
	}

	@Transactional(readOnly = true)
	public PanelProduction getMaxPanelProduction() {
		NavigableMap<Float,List<Panel>> map = new TreeMap<>(this.getPanelSummaries());

		return map.size() > 0
				? new PanelProduction(BigDecimal.valueOf(map.lastEntry().getKey()), BigDecimal.ZERO, map.lastEntry().getValue().size())
				: new PanelProduction(BigDecimal.ZERO,BigDecimal.ZERO,0);
	}

	@Transactional(readOnly = true)
	public Map<Float, List<Panel>> getPanelSummaries() {
		try {
			List<Panel> panels = this.getLastEvent().getPanels();
			panels.sort((o1, o2) -> Float.compare(o1.getPanelValue(), o2.getPanelValue()) * -1);
			return panels.stream()
					.collect(Collectors.groupingBy(Panel::bucket, LinkedHashMap::new, Collectors.toList()));
		} catch (Exception e) {
			log.error("getPanelSummaries error : {}", e.getMessage(), e);
		}
		return new LinkedHashMap<>();
	}

	public List<PanelSummary> getPanelProduction() {
		return panelRepository.getPanelSummaries(getMidnight());
	}

	// When a summary record is null the list is not continuous so fill missing values
	public List<Summary> getLastDurationTotalsContinuous(String duration) {
		List<Summary> dbValues = this.getLastDurationTotals(duration);
		List<Summary> result = new ArrayList<>();

		LocalDate dateIndex = Calculators.calculateStartDateFromDuration(duration);
		int dbIndex = 0;

		while (dbIndex < dbValues.size()) {
			if (dateIndex.isEqual(dbValues.get(dbIndex).getDate())) {
				result.add(dbValues.get(dbIndex++));
				dateIndex = dateIndex.plusDays(1);
			} else if (dateIndex.isBefore(dbValues.get(dbIndex).getDate())) {
				result.add(new Summary(dateIndex));
				dateIndex = dateIndex.plusDays(1);
			} else {
				result.add(dbValues.get(dbIndex++));
			}
		}

		return result;
	}

	public List<Summary> getLastDurationTotals(String duration) {
		return this.getSummaries(Calculators.calculateStartDateFromDuration(duration), Calculators.calculateEndDateFromDuration(duration));
	}

	public List<Summary> getSummaries(LocalDate from, LocalDate to) {
		List<Summary> summaries = summaryRepository.findSummariesByDateBetweenOrderByDateAsc(from, to);
		log.debug("Days returned {}", summaries.size());
		return summaries;
	}

	public BigDecimal calculateCostsForToday() {
		return Calculators.calculateFinancial(eventRepository.findExcessConsumptionAfter(getMidnight()), properties.getChargePerKiloWatt(), "Cost", properties.getRefreshAsMinutes());
	}

	public BigDecimal calculatePaymentForToday() {
		return Calculators.calculateFinancial(eventRepository.findExcessProductionAfter(getMidnight()), properties.getPaymentPerKiloWatt(), "Payment", properties.getRefreshAsMinutes());
	}

	public BigDecimal calculateSavingsForToday() {
		Long totalWatts = eventRepository.findTotalProductionAfter(getMidnight());
		Long excessWatts = eventRepository.findExcessProductionAfter(getMidnight());
		return Calculators.calculateFinancial( totalWatts - excessWatts , properties.getChargePerKiloWatt(), "Savings", properties.getRefreshAsMinutes());
	}

	public Long calculateMaxProduction() {
		return eventRepository.findMaxProductionAfter(getMidnight());
	}

	public BigDecimal calculateGridImport() {
		BigDecimal watts = BigDecimal.valueOf(eventRepository.findExcessConsumptionAfter(getMidnight()));
		return Convertors.convertToKiloWattHours(watts, properties.getRefreshAsMinutes());
	}

	public BigDecimal calculateTotalProduction() {
		BigDecimal watts = BigDecimal.valueOf(eventRepository.findTotalProductionAfter(getMidnight()));
		return Convertors.convertToKiloWattHours(watts, properties.getRefreshAsMinutes());
	}

	public BigDecimal calculateTotalConsumption() {
		BigDecimal watts = BigDecimal.valueOf(eventRepository.findTotalConsumptionAfter(getMidnight()));
		return Convertors.convertToKiloWattHours(watts, properties.getRefreshAsMinutes());
	}

	private LocalDateTime getMidnight() {
		LocalDate now = LocalDate.now();
		return now.atStartOfDay();
	}
}
