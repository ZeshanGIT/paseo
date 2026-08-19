const path = require("node:path");
const {
  withAppBuildGradle,
  withGradleProperties,
  withSettingsGradle,
} = require("expo/config-plugins");

const WEAR_PROJECT = "paseo-wear";

function withPaseoWear(config) {
  const phoneApplicationId = config.android?.package;
  if (!phoneApplicationId) {
    throw new Error("Paseo Wear requires an Android package name");
  }

  config = withGradleProperties(config, (modConfig) => {
    modConfig.modResults = modConfig.modResults.filter(
      (item) => item.key !== "paseoWearApplicationId",
    );
    modConfig.modResults.push({
      type: "property",
      key: "paseoWearApplicationId",
      value: phoneApplicationId,
    });
    return modConfig;
  });

  config = withSettingsGradle(config, (modConfig) => {
    const includeLine = `include ':${WEAR_PROJECT}'`;
    if (modConfig.modResults.contents.includes(includeLine)) return modConfig;
    const relativeModulePath = path.relative(
      modConfig.modRequest.platformProjectRoot,
      path.join(modConfig.modRequest.projectRoot, "modules", WEAR_PROJECT),
    );
    const projectBlock = `${includeLine}\nproject(':${WEAR_PROJECT}').projectDir = new File(rootDir, '${relativeModulePath}')`;
    const appInclude = "include ':app'";
    if (!modConfig.modResults.contents.includes(appInclude)) {
      throw new Error("Could not add Paseo Wear app to settings.gradle");
    }
    modConfig.modResults.contents = modConfig.modResults.contents.replace(
      appInclude,
      `${appInclude}\n${projectBlock}`,
    );
    return modConfig;
  });

  return withAppBuildGradle(config, (modConfig) => {
    const dependency = `wearApp project(':${WEAR_PROJECT}')`;
    if (modConfig.modResults.contents.includes(dependency)) return modConfig;
    const dependenciesBlock = "dependencies {";
    if (!modConfig.modResults.contents.includes(dependenciesBlock)) {
      throw new Error("Could not add Paseo Wear app dependency to app/build.gradle");
    }
    modConfig.modResults.contents = modConfig.modResults.contents.replace(
      dependenciesBlock,
      `${dependenciesBlock}\n    ${dependency}`,
    );
    return modConfig;
  });
}

module.exports = withPaseoWear;
