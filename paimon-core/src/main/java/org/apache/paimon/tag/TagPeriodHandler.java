/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.tag;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.Timestamp;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Handle time for tag. */
public interface TagPeriodHandler {

    DateTimeFormatter HOUR_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendValue(YEAR, 1, 10, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(MONTH_OF_YEAR, 2, 2, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(DAY_OF_MONTH, 2, 2, SignStyle.NORMAL)
                    .appendLiteral(" ")
                    .appendValue(HOUR_OF_DAY, 2, 2, SignStyle.NORMAL)
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.LENIENT);

    DateTimeFormatter DAY_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendValue(YEAR, 1, 10, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(MONTH_OF_YEAR, 2, 2, SignStyle.NORMAL)
                    .appendLiteral('-')
                    .appendValue(DAY_OF_MONTH, 2, 2, SignStyle.NORMAL)
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.LENIENT);

    void validateDelay(Duration delay);

    LocalDateTime tagToTime(String tag);

    LocalDateTime normalizeToPreviousTag(LocalDateTime time);

    String timeToTag(LocalDateTime time);

    LocalDateTime nextTagTime(LocalDateTime time);

    /** Base implementation of {@link TagPeriodHandler}. */
    abstract class BaseTagPeriodHandler implements TagPeriodHandler {

        protected abstract Duration onePeriod();

        protected abstract DateTimeFormatter formatter();

        @Override
        public void validateDelay(Duration delay) {
            checkArgument(onePeriod().compareTo(delay) > 0);
        }

        @Override
        public LocalDateTime tagToTime(String tag) {
            return LocalDateTime.parse(tag, formatter());
        }

        @Override
        public LocalDateTime normalizeToPreviousTag(LocalDateTime time) {
            long mills = Timestamp.fromLocalDateTime(time).getMillisecond();
            long periodMills = onePeriod().toMillis();
            LocalDateTime normalized =
                    Timestamp.fromEpochMillis((mills / periodMills) * periodMills)
                            .toLocalDateTime();
            return normalized.minus(onePeriod());
        }

        @Override
        public String timeToTag(LocalDateTime time) {
            return time.format(formatter());
        }

        @Override
        public LocalDateTime nextTagTime(LocalDateTime time) {
            return time.plus(onePeriod());
        }
    }

    /** Hourly {@link TagPeriodHandler}. */
    class HourlyTagPeriodHandler extends BaseTagPeriodHandler {

        static final Duration ONE_PERIOD = Duration.ofHours(1);

        @Override
        protected Duration onePeriod() {
            return ONE_PERIOD;
        }

        @Override
        protected DateTimeFormatter formatter() {
            return HOUR_FORMATTER;
        }
    }

    /** Daily {@link TagPeriodHandler}. */
    class DailyTagPeriodHandler extends BaseTagPeriodHandler {

        static final Duration ONE_PERIOD = Duration.ofDays(1);

        @Override
        protected Duration onePeriod() {
            return ONE_PERIOD;
        }

        @Override
        protected DateTimeFormatter formatter() {
            return DAY_FORMATTER;
        }

        @Override
        public LocalDateTime tagToTime(String tag) {
            return LocalDate.parse(tag, formatter()).atStartOfDay();
        }
    }

    /** Two Hours {@link TagPeriodHandler}. */
    class TwoHoursTagPeriodHandler extends BaseTagPeriodHandler {

        static final Duration ONE_PERIOD = Duration.ofHours(2);

        @Override
        protected Duration onePeriod() {
            return ONE_PERIOD;
        }

        @Override
        protected DateTimeFormatter formatter() {
            return HOUR_FORMATTER;
        }
    }

    static TagPeriodHandler create(CoreOptions options) {
        switch (options.tagCreationPeriod()) {
            case DAILY:
                return new DailyTagPeriodHandler();
            case HOURLY:
                return new HourlyTagPeriodHandler();
            case TWO_HOURS:
                return new TwoHoursTagPeriodHandler();
            default:
                throw new UnsupportedOperationException(
                        "Unsupported " + options.tagCreationPeriod());
        }
    }
}
