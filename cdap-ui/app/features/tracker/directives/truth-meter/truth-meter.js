/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

angular.module(PKG.name + '.feature.tracker')
  .directive('myTruthMeter', function () {

    return {
      restrict: 'E',
      scope: {
        score: '=',
        width: '@',
        showInfoIcon: '='
      },
      templateUrl: '/assets/features/tracker/directives/truth-meter/truth-meter.html',
      controller: function() {
        this.score = this.score || 0;
        this.showInfoIcon = this.showInfoIcon || false;
      },
      bindToController: true,
      controllerAs: 'TruthMeter'
    };
  });
