/*  Copyright (C) 2021-2024 Andreas Shimokawa

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.huami.amazfitgts2;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.devices.huami.HuamiFWHelper;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.amazfitgts2.AmazfitGTS2eFirmwareInfo;

public class AmazfitGTS2eFWHelper extends HuamiFWHelper {

    public AmazfitGTS2eFWHelper(final Uri uri, final Context context) throws IOException {
        super(uri, context);
    }

    @Override
    protected void determineFirmwareInfo(byte[] wholeFirmwareBytes) {
        firmwareInfo = new AmazfitGTS2eFirmwareInfo(wholeFirmwareBytes);
        if (!firmwareInfo.isHeaderValid()) {
            throw new IllegalArgumentException("Not an Amazfit GTR 2e firmware");
        }
    }
}
