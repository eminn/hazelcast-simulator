package com.hazelcast.simulator.tests.synthetic;

import com.hazelcast.client.impl.client.PartitionClientRequest;
import com.hazelcast.nio.serialization.PortableReader;
import com.hazelcast.nio.serialization.PortableWriter;
import com.hazelcast.spi.Operation;

import java.io.IOException;
import java.security.Permission;

public class SyntheticRequest extends PartitionClientRequest {

    private int syncBackupCount;
    private int asyncBackupCount;
    private long backupDelayNanos;
    private int partitionId;

    public SyntheticRequest() {
    }

    public SyntheticRequest(int syncBackupCount, int asyncBackupCount, long backupDelayNanos, String serviceName) {
        this.syncBackupCount = syncBackupCount;
        this.asyncBackupCount = asyncBackupCount;
        this.backupDelayNanos = backupDelayNanos;
    }

    public void setLocalPartitionId(int partitionId) {
        this.partitionId = partitionId;
    }

    @Override
    protected Operation prepareOperation() {
        SyntheticOperation operation = new SyntheticOperation((byte) syncBackupCount, (byte) asyncBackupCount, backupDelayNanos);
        operation.setPartitionId(partitionId);

        return operation;
    }

    @Override
    protected int getPartition() {
        return partitionId;
    }

    @Override
    public String getServiceName() {
        return null;
    }

    @Override
    public int getFactoryId() {
        return SyntheticRequestPortableFactory.FACTORY_ID;
    }

    @Override
    public int getClassId() {
        return 1;
    }

    @Override
    public Permission getRequiredPermission() {
        return null;
    }

    @Override
    public void write(PortableWriter writer) throws IOException {
        super.write(writer);

        writer.writeInt("syncBackupCount", syncBackupCount);
        writer.writeInt("asyncBackupCount", asyncBackupCount);
        writer.writeLong("backupDelayNanos", backupDelayNanos);
        writer.writeInt("partitionId", partitionId);
    }

    @Override
    public void read(PortableReader reader) throws IOException {
        super.read(reader);

        syncBackupCount = reader.readInt("syncBackupCount");
        asyncBackupCount = reader.readInt("asyncBackupCount");
        backupDelayNanos = reader.readLong("backupDelayNanos");
        partitionId = reader.readInt("partitionId");
    }
}
