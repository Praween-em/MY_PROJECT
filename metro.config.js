const { getDefaultConfig } = require('expo/metro-config');

/** @type {import('expo/metro-config').MetroConfig} */
const config = getDefaultConfig(__dirname);

// Keep the API / generators out of the phone bundle.
config.resolver.blockList = [
  /[\\/]backend[\\/]/,
  /[\\/]scripts[\\/]/,
].concat(config.resolver.blockList);

module.exports = config;
