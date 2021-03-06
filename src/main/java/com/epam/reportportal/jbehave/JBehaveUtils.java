/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/agent-java-jbehave
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.epam.reportportal.jbehave;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.jbehave.core.model.Meta;
import org.jbehave.core.model.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epam.reportportal.guice.Injector;
import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.listeners.ReportPortalListenerContext;
import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.ReportPortalService;
import com.epam.ta.reportportal.ws.model.EntryCreatedRS;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.reportportal.restclient.endpoint.exception.RestEndpointIOException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.*;
import com.google.common.collect.Iterables;

/**
 * Set of usefull utils related to JBehave -> ReportPortal integration
 *
 * @author Andrei Varabyeu
 */
class JBehaveUtils {

	private JBehaveUtils() {
		// static utilities class
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JBehaveUtils.class);

	private static final int MAX_NAME_LENGTH = 256;

	private static final String KEY_VALUE_SEPARATOR = ":";

	private static final String META_PARAMETER_SEPARATOR = " ";

	@VisibleForTesting
	static final Pattern STEP_NAME_PATTERN = Pattern.compile("<(.*?)>");

	/**
	 * Using suppliers to avoid loading singleton from incorrect classloader
	 * (for example, during logging system initialization)
	 */
	private static Supplier<ListenerParameters> parameters = Suppliers.memoize(new Supplier<ListenerParameters>() {

		@Override
		public ListenerParameters get() {
			return Injector.getInstance().getBean(ListenerParameters.class);
		}
	});

	private static Supplier<ReportPortalService> reportPortalService = Suppliers.memoize(new Supplier<ReportPortalService>() {

		@Override
		public ReportPortalService get() {
			return Injector.getInstance().getBean(ReportPortalService.class);
		}
	});

	private static final AtomicBoolean RP_IS_DOWN = new AtomicBoolean(false);

	/**
	 * Starts JBehave launch in ReportPortal
	 */
	public static void startLaunch() {
		StartLaunchRQ rq = new StartLaunchRQ();
		rq.setName(parameters.get().getLaunchName());
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setMode(parameters.get().getMode());
		rq.setTags(parameters.get().getTags());
		rq.setDescription(parameters.get().getDescription());
		try {
			EntryCreatedRS rs = reportPortalService.get().startLaunch(rq);
			JBehaveContext.setCurrentLaunch(rs.getId());
		} catch (RestEndpointIOException e) {
			LOGGER.error("Unable to start launch in ReportPortal", e);
			RP_IS_DOWN.set(true);
		}
	}

	/**
	 * Finishes JBehaveLaunch in ReportPortal
	 */
	public static void finishLaunch() {

		if (RP_IS_DOWN.get() || null == JBehaveContext.getCurrentLaunch()) {
			return;
		}

		FinishExecutionRQ finishLaunchRq = new FinishExecutionRQ();
		finishLaunchRq.setEndTime(Calendar.getInstance().getTime());
		try {
			reportPortalService.get().finishLaunch(JBehaveContext.getCurrentLaunch(), finishLaunchRq);
		} catch (RestEndpointIOException e) {
			LOGGER.error("Unable to finish launch in ReportPortal", e);
		} finally {
			JBehaveContext.setCurrentLaunch(null);
		}

	}

	/**
	 * Starts story (test suite level) in ReportPortal
	 *
	 * @param story
	 */
	public static void startStory(Story story, boolean givenStory) {

		if (RP_IS_DOWN.get()) {
			return;
		}

		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setLaunchId(JBehaveContext.getCurrentLaunch());

		Set<String> metaProperties = story.getMeta().getPropertyNames();
		Map<String, String> metaMap = new HashMap<String, String>(metaProperties.size());
		for (String metaProperty : metaProperties) {
			metaMap.put(metaProperty, story.getMeta().getProperty(metaProperty));
		}

		if (Strings.isNullOrEmpty(story.getDescription().asString())) {
			rq.setDescription(story.getDescription().asString() + "\n" + joinMeta(metaMap));
		}
		rq.setName(normalizeLength(story.getName()));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("STORY");
		try {
			EntryCreatedRS rs;
			JBehaveContext.Story currentStory;

			if (givenStory) {
				/*
				 * Given story means inner story. That's why we need to create
				 * new story and assign parent to it
				 */
				String parent = JBehaveContext.getCurrentStory().getCurrentScenario() != null
						? JBehaveContext.getCurrentStory().getCurrentScenario() : JBehaveContext.getCurrentStory().getCurrentStoryId();
				rs = reportPortalService.get().startTestItem(parent, rq);
				currentStory = new JBehaveContext.Story();
				currentStory.setParent(JBehaveContext.getCurrentStory());
				JBehaveContext.setCurrentStory(currentStory);
			} else {
				rs = reportPortalService.get().startRootTestItem(rq);
				currentStory = JBehaveContext.getCurrentStory();
			}
			currentStory.setCurrentStoryId(rs.getId());
			currentStory.setStoryMeta(story.getMeta());
		} catch (RestEndpointIOException e) {
			e.printStackTrace(); // NOSONAR
			LOGGER.error("Unable to start story in ReportPortal", e);
		}

	}

	/**
	 * Finishes story in ReportPortal
	 */
	public static void finishStory() {

		JBehaveContext.Story currentStory = JBehaveContext.getCurrentStory();

		if (RP_IS_DOWN.get() || null == currentStory.getCurrentStoryId()) {
			return;
		}

		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());

		/*
		 * TODO HZ!
		 */

		rq.setStatus(Statuses.PASSED);
		try {
			reportPortalService.get().finishTestItem(currentStory.getCurrentStoryId(), rq);
		} catch (RestEndpointIOException e) {
			LOGGER.error("Unable to finish story in ReportPortal", e);
			e.printStackTrace(); // NOSONAR
		} finally {
			currentStory.setCurrentStoryId(null);
		}

		if (currentStory.hasParent()) {
			JBehaveContext.setCurrentStory(currentStory.getParent());
		}

	}

	/**
	 * Starts step in ReportPortal (TestStep level)
	 *
	 * @param step
	 */
	public static void startStep(String step) {

		if (RP_IS_DOWN.get()) {
			return;
		}

		JBehaveContext.Story currentStory = JBehaveContext.getCurrentStory();

		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setLaunchId(JBehaveContext.getCurrentLaunch());

		if (currentStory.hasExamples() && currentStory.getExamples().hasStep(step)) {
			StringBuilder name = new StringBuilder();
			name.append("[").append(currentStory.getExamples().getCurrentExample()).append("] ")
					.append(expandParameters(step, currentStory.getExamples().getCurrentExampleParams()));
			rq.setName(normalizeLength(name.toString()));
			rq.setDescription(joinMeta(currentStory.getExamples().getCurrentExampleParams()));

		} else {
			rq.setName(normalizeLength(step));
			rq.setDescription(joinMetas(currentStory.getStoryMeta(), currentStory.getScenarioMeta()));
		}

		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("STEP");
		try {
			EntryCreatedRS rs = reportPortalService.get().startTestItem(currentStory.getCurrentScenario(), rq);
			currentStory.setCurrentStep(rs.getId());
			ReportPortalListenerContext.setRunningNowItemId(rs.getId());
		} catch (RestEndpointIOException e) {
			e.printStackTrace(); // NOSONAR
			LOGGER.error("Unable to start step in ReportPortal", e);
		}

	}

	/**
	 * Finishes step in ReportPortal
	 *
	 * @param status
	 */
	public static void finishStep(String status) {

		JBehaveContext.Story currentStory = JBehaveContext.getCurrentStory();

		if (RP_IS_DOWN.get() || null == currentStory.getCurrentStep()) {
			return;
		}

		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		rq.setStatus(status);

		try {
			reportPortalService.get().finishTestItem(currentStory.getCurrentStep(), rq);
		} catch (RestEndpointIOException e) {
			LOGGER.error("Unable to finish story in ReportPortal", e);
		} finally {
			currentStory.setCurrentStep(null);
			ReportPortalListenerContext.setRunningNowItemId(null);
		}
	}

	/**
	 * Starts scenario in ReportPortal (test level)
	 *
	 * @param scenario
	 */
	public static void startScenario(String scenario) {

		if (RP_IS_DOWN.get()) {
			return;
		}

		JBehaveContext.Story currentStory = JBehaveContext.getCurrentStory();
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setLaunchId(JBehaveContext.getCurrentLaunch());
		rq.setName(normalizeLength(expandParameters(scenario, metasToMap(currentStory.getStoryMeta(), currentStory.getScenarioMeta()))));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("SCENARIO");
		rq.setDescription(joinMetas(currentStory.getStoryMeta(), currentStory.getScenarioMeta()));
		try {
			EntryCreatedRS rs = reportPortalService.get().startTestItem(currentStory.getCurrentStoryId(), rq);
			currentStory.setCurrentScenario(rs.getId());
		} catch (RestEndpointIOException e) {
			e.printStackTrace(); // NOSONAR
			LOGGER.error("Unable to start scenario in ReportPortal", e);
		}
	}

	/**
	 * Finishes scenario in ReportPortal
	 */
	public static void finishScenario(String status) {

		JBehaveContext.Story currentStory = JBehaveContext.getCurrentStory();
		if (RP_IS_DOWN.get() || null == currentStory.getCurrentScenario()) {
			return;
		}

		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		rq.setStatus(status);
		try {
			reportPortalService.get().finishTestItem(currentStory.getCurrentScenario(), rq);
		} catch (RestEndpointIOException e) {
			LOGGER.error("Unable to finish scenario in ReportPortal", e);
		} finally {
			currentStory.setCurrentScenario(null);
		}
	}

	/**
	 * Finishes scenario in ReportPortal
	 */
	public static void finishScenario() {
		finishScenario(Statuses.PASSED);
	}

	/**
	 * Iterate over started test items cache and remove all not finished item
	 *
	 * @param status
	 */
	public static void makeSureAllItemsFinished(String status) {

		if (RP_IS_DOWN.get()) {
			return;
		}

		Deque<String> items = JBehaveContext.getItemsCache();
		String item;
		while (null != (item = items.poll())) {
			FinishTestItemRQ rq = new FinishTestItemRQ();
			rq.setEndTime(Calendar.getInstance().getTime());
			rq.setStatus(status);
			try {
				reportPortalService.get().finishTestItem(item, rq);
			} catch (Exception e) {
				LOGGER.error("Unable to finish started test items in ReportPortal", e);
			}
		}
	}

	private static String joinMeta(Meta meta) {

		if (null == meta) {
			return StringUtils.EMPTY;
		}
		Iterator<String> metaParametersIterator = meta.getPropertyNames().iterator();

		if (metaParametersIterator.hasNext()) {
			StringBuilder appendable = new StringBuilder();
			String firstParameter = metaParametersIterator.next();
			appendable.append(joinMeta(firstParameter, meta.getProperty(firstParameter)));
			while (metaParametersIterator.hasNext()) {
				String nextParameter = metaParametersIterator.next();
				appendable.append(META_PARAMETER_SEPARATOR);
				appendable.append(joinMeta(nextParameter, meta.getProperty(nextParameter)));
			}
			return appendable.toString();
		}
		return StringUtils.EMPTY;

	}

	private static Map<String, String> metaToMap(Meta meta) {
		if (null == meta) {
			return Collections.emptyMap();
		}
		Map<String, String> metaMap = new HashMap<String, String>(meta.getPropertyNames().size());
		for (String name : meta.getPropertyNames()) {
			metaMap.put(name, meta.getProperty(name));
		}
		return metaMap;

	}

	// TODO rename as join metas
	private static Map<String, String> metasToMap(Meta... metas) {
		if (null != metas && metas.length > 0) {
			Map<String, String> metaMap = new HashMap<String, String>();
			for (Meta meta : metas) {
				metaMap.putAll(metaToMap(meta));
			}
			return metaMap;
		} else {
			return Collections.emptyMap();
		}
	}

	private static String joinMeta(Map<String, String> metaParameters) {
		Iterator<Entry<String, String>> metaParametersIterator = metaParameters.entrySet().iterator();

		if (metaParametersIterator.hasNext()) {
			StringBuilder appendable = new StringBuilder();
			Entry<String, String> firstParameter = metaParametersIterator.next();
			appendable.append(joinMeta(firstParameter.getKey(), firstParameter.getValue()));
			while (metaParametersIterator.hasNext()) {
				Entry<String, String> nextParameter = metaParametersIterator.next();
				appendable.append(META_PARAMETER_SEPARATOR);
				appendable.append(joinMeta(nextParameter.getKey(), nextParameter.getValue()));
			}
			return appendable.toString();
		}
		return StringUtils.EMPTY;

	}

	private static String joinMeta(String key, String value) {
		if (null == value) {
			return key;
		}
		if (value.toLowerCase().startsWith("http")) {
			String text;
			if (value.toLowerCase().contains("jira")) {
				text = key + KEY_VALUE_SEPARATOR + StringUtils.substringAfterLast(value, "/");
			} else {
				text = key;
			}
			return wrapAsLink(value, text);
		} else {
			return key + KEY_VALUE_SEPARATOR + value;
		}

	}

	// TODO should be removed since RP doesn't render HTML in the description
	@Deprecated
	private static String wrapAsLink(String href, String text) {
		return new StringBuilder("<a href=\"").append(href).append("\">").append(text).append("</a>").toString();
	}

	private static String joinMetas(Meta... metas) {
		return Joiner.on(META_PARAMETER_SEPARATOR).join(Iterables.transform(Arrays.asList(metas), new Function<Meta, String>() {

			@Override
			public String apply(Meta input) {
				return joinMeta(input);
			}
		}));
	}

	@VisibleForTesting
	static String expandParameters(String stepName, Map<String, String> parameters) {
		Matcher m = STEP_NAME_PATTERN.matcher(stepName);
		StringBuffer buffer = new StringBuffer();
		while (m.find()) {
			if (parameters.containsKey(m.group(1))) {
				m.appendReplacement(buffer, parameters.get(m.group(1)));
			}
		}
		m.appendTail(buffer);
		return buffer.toString();
	}

	static String normalizeLength(String string) {
		if (null != string && string.length() > MAX_NAME_LENGTH) {
			return string.substring(0, MAX_NAME_LENGTH - 1);
		}
		return string;
	}

}
