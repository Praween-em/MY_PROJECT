// AWS Lambda entry point — wraps the Express app using serverless-http
const serverless = require('serverless-http');
const app = require('./index');

module.exports.handler = serverless(app);
