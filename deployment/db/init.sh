#!/bin/bash
set -euo pipefail

DB_NAME="healthcare"

mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" admin <<'EOF'
const dbName = "healthcare";
const database = db.getSiblingDB(dbName);

function collectionExists(name) {
  return database.getCollectionNames().includes(name);
}

const rolesCollection = database.getCollection("roles");
const usersCollection = database.getCollection("users");
const doctorsCollection = database.getCollection("doctors");
const patientsCollection = database.getCollection("patients");

if (!collectionExists("roles") || rolesCollection.countDocuments({}) === 0) {
  rolesCollection.insertMany([
    { name: "ADMIN" },
    { name: "DOCTOR" },
    { name: "PATIENT" }
  ]);
  print("Created roles: ADMIN, DOCTOR, PATIENT");
} else {
  print("Roles already exist. Skipping role creation.");
}

const adminRole = rolesCollection.findOne({ name: "ADMIN" });
const doctorRole = rolesCollection.findOne({ name: "DOCTOR" });
const patientRole = rolesCollection.findOne({ name: "PATIENT" });

if (!collectionExists("users")) {
  usersCollection.insertMany([
    {
      username: "admin",
      email: "noreplyhungrycoders@gmail.com",
      password: "$2y$10$4NtLeIl0IqGhpIRNuRvTneklU.u57FXR2/M29WVbZDXsvOrUWJoRW",
      roles: [{ $ref: "roles", $id: adminRole._id }]
    },
    {
      username: "doctor",
      email: "doctorhungrycoders@gmail.com",
      password: "$2y$10$LSW3OFc1BWCcRootth9Lh.9s3l10Xfsw4lixtRoYSWmMY2Ci/tmuq",
      roles: [{ $ref: "roles", $id: doctorRole._id }]
    },
    {
      username: "patient",
      email: "patienthungrycoders@gmail.com",
      password: "$2y$10$FvnoW4eiSZuQ212..v4J.uOaa8fTILNmIy57Jzcq90503/N6BCzZO",
      roles: [{ $ref: "roles", $id: patientRole._id }]
    }
  ]);
  print("Inserted default users.");
} else {
  print("Users collection already exists. Skipping insertion.");
}

if (!collectionExists("doctors")) {
  doctorsCollection.insertOne({
    _id: "5d789884-6b54-4639-8904-b38b8f727293",
    firstName: "Sample",
    lastName: "Doctor",
    email: "doctorhungrycoders@gmail.com",
    phone: "123456789",
    speciality: "Gynic, General Medicine",
    yearsOfExperience: 4,
    status: "AVAILABLE"
  });
  print("Inserted sample doctor data.");
} else {
  print("Doctors collection already exists. Skipping insertion.");
}

if (!collectionExists("patients")) {
  patientsCollection.insertOne({
    _id: "756b6a6e-b07f-4484-a7b1-8bae317767bf",
    firstName: "Sample",
    lastName: "Patient",
    email: "patienthungrycoders@gmail.com",
    phone: "123456789",
    age: 30
  });
  print("Inserted sample patient data.");
} else {
  print("Patients collection already exists. Skipping insertion.");
}

print("Data initialization complete!");
EOF
