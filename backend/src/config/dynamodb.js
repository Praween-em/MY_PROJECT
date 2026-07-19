/**
 * dynamodb.js — shared DynamoDB v3 client
 *
 * On AWS Lambda: credentials come automatically from the IAM execution role.
 * For local dev with serverless-offline: set AWS credentials in environment or ~/.aws/credentials.
 */

const { DynamoDBClient } = require('@aws-sdk/client-dynamodb');
const { DynamoDBDocumentClient } = require('@aws-sdk/lib-dynamodb');

const client = new DynamoDBClient({
  region: process.env.AWS_REGION || 'ap-south-1',
  ...(process.env.IS_OFFLINE === 'true' && {
    endpoint: 'http://localhost:8000',
  }),
});

const db = DynamoDBDocumentClient.from(client);

const TABLE = process.env.DYNAMODB_TABLE || 'playnix-users';

module.exports = { db, TABLE };
