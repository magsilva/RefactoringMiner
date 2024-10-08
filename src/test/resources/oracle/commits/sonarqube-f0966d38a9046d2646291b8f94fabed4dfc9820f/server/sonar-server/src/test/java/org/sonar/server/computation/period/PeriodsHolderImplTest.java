/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation.period;

import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.assertj.core.api.Assertions.assertThat;

public class PeriodsHolderImplTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void get_periods() throws Exception {
    List<Period> periods = new ArrayList<>();
    periods.add(new Period(1, "mode", null, 1000L));

    PeriodsHolderImpl periodsHolder = new PeriodsHolderImpl();
    periodsHolder.setPeriods(periods);

    assertThat(periodsHolder.getPeriods()).hasSize(1);
  }

  @Test
  public void fail_to_get_periods_if_not_initialized() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Periods have not been initialized yet");

    new PeriodsHolderImpl().getPeriods();
  }
}
